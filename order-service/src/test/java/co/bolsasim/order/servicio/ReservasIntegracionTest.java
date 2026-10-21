package co.bolsasim.order.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.EstadoOrden;
import co.bolsasim.eventos.Lado;
import co.bolsasim.eventos.ReservaProcesada;
import co.bolsasim.eventos.ResultadoReserva;
import co.bolsasim.eventos.Topicos;
import co.bolsasim.eventos.TipoOrden;
import co.bolsasim.order.orden.EventoImposibleException;
import co.bolsasim.order.orden.Orden;
import co.bolsasim.order.orden.RepositorioOrdenes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
 * Prueba de integración de reservations.events en order-service contra PostgreSQL real (RNF-36): la reserva
 * aceptada deja el comando del motor en el outbox, la rechazada deja la orden RECHAZADA, y la repetida o la
 * imposible no cambian nada. Los eventos se aplican llamando al servicio directamente, como lo hace el listener;
 * Kafka no hace falta.
 *
 * <p>Necesita Docker. Si Docker está cerrado, la prueba se salta en vez de fallar.
 */
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "bolsasim.outbox.programado=false"})
@Testcontainers(disabledWithoutDocker = true)
class ReservasIntegracionTest {

    private static final UUID ANA = UUID.randomUUID();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private ServicioReservas servicio;

    @Autowired
    private RepositorioOrdenes ordenes;

    @Autowired
    private JdbcClient jdbc;

    private Orden compra;

    @BeforeEach
    void unaCompraPendienteDe100A5000() {
        jdbc.sql("DELETE FROM evento_procesado").update();
        jdbc.sql("DELETE FROM ejecucion").update();
        jdbc.sql("DELETE FROM outbox").update();
        jdbc.sql("DELETE FROM orden").update();
        compra = ordenes.saveAndFlush(Orden.nueva(
                ComandoOrden.nueva(ANA.toString(), "ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100),
                ANA, "clave-" + UUID.randomUUID(), Instant.now().truncatedTo(ChronoUnit.MICROS)));
    }

    @Test
    void conLaReservaAceptadaElComandoQuedaEnElOutboxYLaOrdenSiguePendiente() {
        assertThat(servicio.aplicar(evento(ResultadoReserva.ACEPTADA, 500_000, null))).isTrue();

        // Sigue PENDIENTE hasta que el motor diga ABIERTA o la ejecute
        assertThat(recargar().getEstado()).isEqualTo(EstadoOrden.PENDIENTE);
        List<Fila> filas = outbox();
        assertThat(filas).hasSize(1);
        assertThat(filas.getFirst().topico()).isEqualTo(Topicos.ORDERS_COMMANDS);
        // La clave es el símbolo, como siempre en orders.commands, y el comando es el de la orden
        assertThat(filas.getFirst().clave()).isEqualTo("ANDX");
        assertThat(filas.getFirst().ordenId()).isEqualTo(compra.getId().toString());
        assertThat(filas.getFirst().accion()).isEqualTo("NUEVA");
        assertThat(filas.getFirst().cantidad()).isEqualTo(100);
    }

    @Test
    void conLaReservaRechazadaLaOrdenQuedaRechazadaConElMotivoYNoVaAlMotor() {
        String motivo = "Saldo insuficiente: tienes 1.000 COP disponibles y la orden necesita 500.000 COP.";

        assertThat(servicio.aplicar(evento(ResultadoReserva.RECHAZADA, 0, motivo))).isTrue();

        Orden rechazada = recargar();
        assertThat(rechazada.getEstado()).isEqualTo(EstadoOrden.RECHAZADA);
        assertThat(rechazada.getMotivo()).isEqualTo(motivo);
        assertThat(outbox()).isEmpty();
    }

    @Test
    void unaReservaRepetidaNoEnviaLaOrdenDosVeces() {
        ReservaProcesada aceptada = evento(ResultadoReserva.ACEPTADA, 500_000, null);

        assertThat(servicio.aplicar(aceptada)).isTrue();
        assertThat(servicio.aplicar(aceptada)).isFalse();

        assertThat(outbox()).hasSize(1);
    }

    @Test
    void unaReservaDeUnaOrdenQueYaNoEstaPendienteNoSeAplicaYNoQuedaNadaAMedias() {
        servicio.aplicar(evento(ResultadoReserva.RECHAZADA, 0, "Saldo insuficiente"));
        ReservaProcesada tardia = evento(ResultadoReserva.ACEPTADA, 500_000, null);

        assertThatThrownBy(() -> servicio.aplicar(tardia)).isInstanceOf(EventoImposibleException.class);

        // Ni el comando ni la marca del evento
        assertThat(recargar().getEstado()).isEqualTo(EstadoOrden.RECHAZADA);
        assertThat(outbox()).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM evento_procesado WHERE id_evento = :id")
                .param("id", tardia.eventoId()).query(Integer.class).single()).isZero();
    }

    @Test
    void unaReservaDeUnaOrdenQueNoExisteOEsDeOtroUsuarioNoSeAplica() {
        ReservaProcesada deNadie = new ReservaProcesada(UUID.randomUUID(), UUID.randomUUID(), ANA.toString(),
                ResultadoReserva.ACEPTADA, 500_000, null, Instant.now());
        ReservaProcesada deOtro = new ReservaProcesada(UUID.randomUUID(), compra.getId(), UUID.randomUUID().toString(),
                ResultadoReserva.ACEPTADA, 500_000, null, Instant.now());

        assertThatThrownBy(() -> servicio.aplicar(deNadie)).isInstanceOf(EventoImposibleException.class);
        assertThatThrownBy(() -> servicio.aplicar(deOtro)).isInstanceOf(EventoImposibleException.class);
        assertThat(recargar().getEstado()).isEqualTo(EstadoOrden.PENDIENTE);
        assertThat(outbox()).isEmpty();
    }

    private ReservaProcesada evento(ResultadoReserva resultado, long monto, String motivo) {
        return new ReservaProcesada(UUID.randomUUID(), compra.getId(), ANA.toString(), resultado, monto, motivo,
                Instant.now());
    }

    private Orden recargar() {
        return ordenes.findById(compra.getId()).orElseThrow();
    }

    private List<Fila> outbox() {
        return jdbc.sql("""
                        SELECT topico, clave, payload ->> 'ordenId' AS orden_id, payload ->> 'accion' AS accion,
                               CAST(payload ->> 'cantidad' AS integer) AS cantidad
                        FROM outbox""")
                .query(Fila.class).list();
    }

    record Fila(String topico, String clave, String ordenId, String accion, int cantidad) {
    }
}
