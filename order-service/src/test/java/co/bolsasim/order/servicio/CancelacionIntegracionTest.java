package co.bolsasim.order.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.bolsasim.eventos.AccionComando;
import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.EjecucionOrden;
import co.bolsasim.eventos.EstadoOrden;
import co.bolsasim.eventos.EventoOrden;
import co.bolsasim.eventos.Lado;
import co.bolsasim.eventos.TipoOrden;
import co.bolsasim.eventos.Topicos;
import co.bolsasim.order.orden.Orden;
import co.bolsasim.order.orden.RepositorioOrdenes;
import co.bolsasim.order.outbox.Outbox;
import co.bolsasim.order.outbox.PublicadorOutbox;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Prueba de integración de la cancelación (HU-08) contra PostgreSQL real (RNF-36): solicitudes simultáneas dejan un
 * solo CANCELAR en el outbox, el CANCELAR sale después de su NUEVA aunque tengan la misma marca de tiempo, y el
 * evento CANCELADA del motor deja la orden cancelada con lo que alcanzó a ejecutar. Kafka es un doble de Mockito y
 * el publicador programado está apagado: cada prueba decide cuándo corre una ronda.
 *
 * <p>Necesita Docker. Si Docker está cerrado, la prueba se salta en vez de fallar.
 */
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "bolsasim.outbox.programado=false"})
@Testcontainers(disabledWithoutDocker = true)
class CancelacionIntegracionTest {

    private static final UUID ANA = UUID.randomUUID();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @MockitoBean
    private KafkaTemplate<String, Object> kafka;

    @Autowired
    private ServicioOrdenes servicio;

    @Autowired
    private ServicioEventosOrden eventos;

    @Autowired
    private RepositorioOrdenes ordenes;

    @Autowired
    private Outbox outbox;

    @Autowired
    private PublicadorOutbox publicador;

    @Autowired
    private TransactionTemplate transaccion;

    @Autowired
    private JdbcClient jdbc;

    private Orden compra;

    /** Una compra de 100 ANDX a 5.000 que el motor ya puso en el libro. */
    @BeforeEach
    void unaCompraAbierta() {
        jdbc.sql("DELETE FROM evento_procesado").update();
        jdbc.sql("DELETE FROM ejecucion").update();
        jdbc.sql("DELETE FROM outbox").update();
        jdbc.sql("DELETE FROM orden").update();
        compra = ordenes.saveAndFlush(Orden.nueva(
                ComandoOrden.nueva(ANA.toString(), "ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100),
                ANA, "clave-" + UUID.randomUUID(), Instant.now().truncatedTo(ChronoUnit.MICROS)));
        eventos.aplicar(evento(EstadoOrden.ABIERTA, 0, null, null));
    }

    @Test
    void ochoCancelacionesALaVezDejanUnSoloCancelarEnElOutbox() throws Exception {
        ExecutorService hilos = Executors.newFixedThreadPool(8);
        List<Future<Orden>> resultados = new ArrayList<>();
        try {
            CountDownLatch largada = new CountDownLatch(1);
            for (int i = 0; i < 8; i++) {
                resultados.add(hilos.submit(() -> {
                    largada.await();
                    return servicio.cancelar(ANA.toString(), compra.getId());
                }));
            }
            largada.countDown();
            // Las ocho reciben la orden (202): la primera publica el comando y las demás ven que ya se pidió
            for (Future<Orden> resultado : resultados) {
                assertThat(resultado.get(30, TimeUnit.SECONDS).getId()).isEqualTo(compra.getId());
            }
        } finally {
            hilos.shutdownNow();
        }

        assertThat(jdbc.sql("""
                        SELECT count(*) FROM outbox
                        WHERE topico = :topico AND payload ->> 'accion' = 'CANCELAR' AND payload ->> 'ordenId' = :orden""")
                .param("topico", Topicos.ORDERS_COMMANDS).param("orden", compra.getId().toString())
                .query(Integer.class).single()).isEqualTo(1);
        Orden pedida = recargar();
        assertThat(pedida.getCancelacionSolicitadaEn()).isNotNull();
        // El estado no cambia hasta que el motor confirme
        assertThat(pedida.getEstado()).isEqualTo(EstadoOrden.ABIERTA);
    }

    @Test
    void elCancelarSaleDespuesDeSuNuevaAunqueTenganLaMismaMarcaDeTiempo() {
        ComandoOrden nueva = compra.comandoParaElMotor();
        transaccion.executeWithoutResult(estado -> outbox.guardar(Topicos.ORDERS_COMMANDS, "ANDX", nueva));
        servicio.cancelar(ANA.toString(), compra.getId());
        // El peor caso del orden de antes, (creado_en, id): la misma marca de tiempo y el CANCELAR con el id menor
        jdbc.sql("UPDATE outbox SET creado_en = :ahora").param("ahora", Instant.now().atOffset(ZoneOffset.UTC)).update();
        jdbc.sql("UPDATE outbox SET id = '00000000-0000-0000-0000-000000000001' WHERE payload ->> 'accion' = 'CANCELAR'")
                .update();
        jdbc.sql("UPDATE outbox SET id = 'ffffffff-ffff-ffff-ffff-ffffffffffff' WHERE payload ->> 'accion' = 'NUEVA'")
                .update();
        when(kafka.send(anyString(), anyString(), any())).thenAnswer(invocacion -> confirmado(invocacion.getArgument(0)));

        assertThat(publicador.publicarPendientes()).isEqualTo(2);

        ArgumentCaptor<Object> comandos = ArgumentCaptor.forClass(Object.class);
        verify(kafka, times(2)).send(eq(Topicos.ORDERS_COMMANDS), eq("ANDX"), comandos.capture());
        assertThat(comandos.getAllValues()).extracting(comando -> ((ComandoOrden) comando).accion())
                .containsExactly(AccionComando.NUEVA, AccionComando.CANCELAR);
    }

    @Test
    void cuandoElMotorConfirmaUnaOrdenParcialQuedaCanceladaConLoQueSeEjecuto() {
        eventos.aplicar(evento(EstadoOrden.PARCIAL, 40, new EjecucionOrden(UUID.randomUUID(), 5_000, 40), null));

        servicio.cancelar(ANA.toString(), compra.getId());
        eventos.aplicar(evento(EstadoOrden.CANCELADA, 40, null, "Cancelada por el usuario."));

        Orden cancelada = recargar();
        assertThat(cancelada.getEstado()).isEqualTo(EstadoOrden.CANCELADA);
        assertThat(cancelada.getCantidadEjecutada()).isEqualTo(40);
        assertThat(cancelada.getPrecioPromedio()).isEqualByComparingTo("5000");
        assertThat(cancelada.getMotivo()).isEqualTo("Cancelada por el usuario.");
        // El evento no borró la marca de la cancelación pedida
        assertThat(cancelada.getCancelacionSolicitadaEn()).isNotNull();
    }

    @Test
    void siSeEjecutaCompletaAntesDeQueElMotorCanceleQuedaEjecutada() {
        servicio.cancelar(ANA.toString(), compra.getId());
        // El motor ejecutó la orden antes de leer el CANCELAR, y después lo ignora: ya no está en el libro
        eventos.aplicar(evento(EstadoOrden.EJECUTADA, 100, new EjecucionOrden(UUID.randomUUID(), 5_000, 100), null));

        Orden ejecutada = recargar();
        assertThat(ejecutada.getEstado()).isEqualTo(EstadoOrden.EJECUTADA);
        assertThat(ejecutada.getCancelacionSolicitadaEn()).isNotNull();
    }

    private EventoOrden evento(EstadoOrden estado, int ejecutada, EjecucionOrden ejecucion, String motivo) {
        return new EventoOrden(UUID.randomUUID(), compra.getId(), ANA.toString(), "ANDX", estado, 100, ejecutada,
                ejecucion, motivo, Instant.now());
    }

    private Orden recargar() {
        return ordenes.findById(compra.getId()).orElseThrow();
    }

    private static CompletableFuture<SendResult<String, Object>> confirmado(String topico) {
        RecordMetadata meta = new RecordMetadata(new TopicPartition(topico, 0), 0, 0, 0, 0, 0);
        return CompletableFuture.completedFuture(new SendResult<>(new ProducerRecord<>(topico, null), meta));
    }
}
