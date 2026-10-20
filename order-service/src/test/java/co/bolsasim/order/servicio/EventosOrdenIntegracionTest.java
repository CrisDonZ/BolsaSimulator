package co.bolsasim.order.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.EjecucionOrden;
import co.bolsasim.eventos.EstadoOrden;
import co.bolsasim.eventos.EventoOrden;
import co.bolsasim.eventos.Lado;
import co.bolsasim.eventos.TipoOrden;
import co.bolsasim.order.orden.EventoImposibleException;
import co.bolsasim.order.orden.Orden;
import co.bolsasim.order.orden.RepositorioOrdenes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Prueba de integración de orders.events en order-service contra PostgreSQL real (RNF-36): cada estado, el
 * precio promedio con varias ejecuciones, el evento repetido y el imposible. Los eventos se aplican llamando al
 * servicio directamente, como lo hace el listener; Kafka no hace falta.
 *
 * <p>Necesita Docker. Si Docker está cerrado, la prueba se salta en vez de fallar.
 */
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "bolsasim.outbox.programado=false"})
@Testcontainers(disabledWithoutDocker = true)
class EventosOrdenIntegracionTest {

    private static final UUID ANA = UUID.randomUUID();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private ServicioEventosOrden servicio;

    @Autowired
    private RepositorioOrdenes ordenes;

    @Autowired
    private JdbcClient jdbc;

    private Orden compra;

    @BeforeEach
    void unaCompraPendienteDe100() {
        jdbc.sql("DELETE FROM evento_procesado").update();
        jdbc.sql("DELETE FROM ejecucion").update();
        jdbc.sql("DELETE FROM outbox").update();
        jdbc.sql("DELETE FROM orden").update();
        compra = ordenes.saveAndFlush(Orden.nueva(
                ComandoOrden.nueva(ANA.toString(), "ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_100L, 100),
                ANA, "clave-" + UUID.randomUUID(), Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }

    @Test
    void abiertaParcialYEjecutadaConElPrecioPromedioDeTodasLasEjecuciones() {
        aplicar(EstadoOrden.ABIERTA, 0, null, null);
        assertThat(recargar().getEstado()).isEqualTo(EstadoOrden.ABIERTA);

        aplicar(EstadoOrden.PARCIAL, 40, ejecucion(5_000, 40), null);
        Orden parcial = recargar();
        assertThat(parcial.getEstado()).isEqualTo(EstadoOrden.PARCIAL);
        assertThat(parcial.getCantidadEjecutada()).isEqualTo(40);
        assertThat(parcial.getPrecioPromedio()).isEqualByComparingTo("5000.0000");

        aplicar(EstadoOrden.EJECUTADA, 100, ejecucion(5_100, 60), null);
        Orden ejecutada = recargar();
        assertThat(ejecutada.getEstado()).isEqualTo(EstadoOrden.EJECUTADA);
        assertThat(ejecutada.getCantidadEjecutada()).isEqualTo(100);
        // (40 × 5000 + 60 × 5100) / 100
        assertThat(ejecutada.getPrecioPromedio()).isEqualByComparingTo("5060.0000");
        assertThat(ejecuciones()).isEqualTo(2);
    }

    @Test
    void elPrecioPromedioSeRedondeaACuatroDecimales() {
        aplicar(EstadoOrden.PARCIAL, 1, ejecucion(5_000, 1), null);
        aplicar(EstadoOrden.PARCIAL, 3, ejecucion(5_005, 2), null);

        // (5000 + 2 × 5005) / 3 = 5003,3333...
        assertThat(recargar().getPrecioPromedio()).isEqualByComparingTo("5003.3333");
    }

    @Test
    void unEventoRepetidoSeAplicaUnaSolaVez() {
        EventoOrden evento = evento(EstadoOrden.PARCIAL, 40, ejecucion(5_000, 40), null);

        assertThat(servicio.aplicar(evento)).isTrue();
        assertThat(servicio.aplicar(evento)).isFalse();

        assertThat(ejecuciones()).isEqualTo(1);
        assertThat(recargar().getCantidadEjecutada()).isEqualTo(40);
    }

    @Test
    void unaOrdenAMercadoEjecutadaAMediasQuedaCanceladaConElMotivo() {
        aplicar(EstadoOrden.PARCIAL, 60, ejecucion(5_000, 60), null);
        aplicar(EstadoOrden.CANCELADA, 60, null,
                "Se ejecutaron 60 de 100 acciones; el resto se canceló porque no había más órdenes de venta.");

        Orden cancelada = recargar();
        assertThat(cancelada.getEstado()).isEqualTo(EstadoOrden.CANCELADA);
        assertThat(cancelada.getCantidadEjecutada()).isEqualTo(60);
        assertThat(cancelada.getPrecioPromedio()).isEqualByComparingTo("5000.0000");
        assertThat(cancelada.getMotivo()).startsWith("Se ejecutaron 60 de 100");
    }

    @Test
    void unEventoQueEjecutariaMasDeLoPedidoNoSeAplicaYNoQuedaNadaAMedias() {
        aplicar(EstadoOrden.PARCIAL, 90, ejecucion(5_000, 90), null);
        EventoOrden imposible = evento(EstadoOrden.EJECUTADA, 120, ejecucion(5_000, 30), null);

        assertThatThrownBy(() -> servicio.aplicar(imposible)).isInstanceOf(EventoImposibleException.class);

        // Ni la ejecución, ni la marca del evento, ni el cambio de la orden
        Orden igual = recargar();
        assertThat(igual.getEstado()).isEqualTo(EstadoOrden.PARCIAL);
        assertThat(igual.getCantidadEjecutada()).isEqualTo(90);
        assertThat(ejecuciones()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM evento_procesado WHERE id_evento = :id")
                .param("id", imposible.eventoId()).query(Integer.class).single()).isZero();
    }

    @Test
    void unEventoDeUnaOrdenQueNoExisteOEsDeOtroUsuarioNoSeAplica() {
        EventoOrden deNadie = new EventoOrden(UUID.randomUUID(), UUID.randomUUID(), ANA.toString(), "ANDX",
                EstadoOrden.ABIERTA, 100, 0, null, null, Instant.now());
        EventoOrden deOtro = new EventoOrden(UUID.randomUUID(), compra.getId(), UUID.randomUUID().toString(), "ANDX",
                EstadoOrden.ABIERTA, 100, 0, null, null, Instant.now());

        assertThatThrownBy(() -> servicio.aplicar(deNadie)).isInstanceOf(EventoImposibleException.class);
        assertThatThrownBy(() -> servicio.aplicar(deOtro)).isInstanceOf(EventoImposibleException.class);
        assertThat(recargar().getEstado()).isEqualTo(EstadoOrden.PENDIENTE);
    }

    private void aplicar(EstadoOrden estado, int ejecutada, EjecucionOrden ejecucion, String motivo) {
        assertThat(servicio.aplicar(evento(estado, ejecutada, ejecucion, motivo))).isTrue();
    }

    private EventoOrden evento(EstadoOrden estado, int ejecutada, EjecucionOrden ejecucion, String motivo) {
        return new EventoOrden(UUID.randomUUID(), compra.getId(), ANA.toString(), "ANDX", estado, 100, ejecutada,
                ejecucion, motivo, Instant.now());
    }

    private static EjecucionOrden ejecucion(long precio, int cantidad) {
        return new EjecucionOrden(UUID.randomUUID(), precio, cantidad);
    }

    private Orden recargar() {
        return ordenes.findById(compra.getId()).orElseThrow();
    }

    private int ejecuciones() {
        return jdbc.sql("SELECT count(*) FROM ejecucion WHERE orden_id = :orden")
                .param("orden", compra.getId()).query(Integer.class).single();
    }
}
