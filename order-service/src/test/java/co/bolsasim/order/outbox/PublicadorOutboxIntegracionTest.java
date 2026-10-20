package co.bolsasim.order.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.Lado;
import co.bolsasim.eventos.TipoOrden;
import co.bolsasim.eventos.Topicos;
import co.bolsasim.order.orden.Orden;
import co.bolsasim.order.orden.RepositorioOrdenes;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Prueba de integración del outbox contra PostgreSQL real (RNF-12, RNF-36). Kafka es un doble de Mockito que
 * puede fallar a propósito, y el publicador programado está apagado: cada prueba decide cuándo corre una ronda.
 *
 * <p>Necesita Docker. Si Docker está cerrado, la prueba se salta en vez de fallar.
 */
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "bolsasim.outbox.programado=false"})
@Testcontainers(disabledWithoutDocker = true)
class PublicadorOutboxIntegracionTest {

    private static final String USUARIO = UUID.randomUUID().toString();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @MockitoBean
    private KafkaTemplate<String, Object> kafka;

    @Autowired
    private PublicadorOutbox publicador;

    @Autowired
    private Outbox outbox;

    @Autowired
    private RepositorioOrdenes ordenes;

    @Autowired
    private TransactionTemplate transaccion;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void outboxVacio() {
        jdbc.sql("DELETE FROM outbox").update();
        jdbc.sql("DELETE FROM orden").update();
    }

    @Test
    void siKafkaFallaElComandoSigueEnElOutboxYSaleEnLaSiguienteRonda() {
        ComandoOrden andx = guardar("ANDX");
        ComandoOrden cafe = guardar("CAFE");

        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.failedFuture(new KafkaException("Kafka caído")));
        assertThat(publicador.publicarPendientes()).isZero();
        assertThat(pendientes()).isEqualTo(2);

        // Kafka vuelve
        when(kafka.send(anyString(), anyString(), any())).thenAnswer(invocacion -> confirmado(invocacion.getArgument(0)));
        assertThat(publicador.publicarPendientes()).isEqualTo(2);
        assertThat(pendientes()).isZero();

        // En cada ronda salen en el orden en que se guardaron, al tópico de siempre, con el símbolo como clave
        // y el mismo comando que se guardó (es un record: equals compara todos los campos)
        ArgumentCaptor<String> claves = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> comandos = ArgumentCaptor.forClass(Object.class);
        verify(kafka, times(4)).send(eq(Topicos.ORDERS_COMMANDS), claves.capture(), comandos.capture());
        assertThat(claves.getAllValues()).containsExactly("ANDX", "CAFE", "ANDX", "CAFE");
        assertThat(comandos.getAllValues()).containsExactly(andx, cafe, andx, cafe);

        // Una ronda más no vuelve a enviar nada
        assertThat(publicador.publicarPendientes()).isZero();
    }

    @Test
    void siUnEnvioFallaSoloSeMarcanLosAnterioresParaNoAlterarElOrden() {
        guardar("ANDX");
        ComandoOrden segundo = guardar("ANDX");
        ComandoOrden tercero = guardar("ANDX");
        when(kafka.send(anyString(), anyString(), any())).thenAnswer(invocacion ->
                invocacion.getArgument(2).equals(segundo)
                        ? CompletableFuture.failedFuture(new KafkaException("Kafka no confirmó"))
                        : confirmado(invocacion.getArgument(0)));

        assertThat(publicador.publicarPendientes()).isEqualTo(1);

        // El tercero se envió, pero no cuenta: el segundo debe salir antes. Los dos se reintentan
        assertThat(jdbc.sql("SELECT payload ->> 'ordenId' FROM outbox WHERE publicado_en IS NULL ORDER BY creado_en")
                .query(String.class).list())
                .containsExactly(segundo.ordenId().toString(), tercero.ordenId().toString());
    }

    @Test
    void siOtraInstanciaEstaPublicandoEstaSeSaltaLaRonda() throws Exception {
        guardar("ANDX");
        when(kafka.send(anyString(), anyString(), any())).thenAnswer(invocacion -> confirmado(invocacion.getArgument(0)));

        // Otra instancia de order-service tiene el bloqueo del publicador
        try (Connection otraInstancia = dataSource.getConnection(); Statement sentencia = otraInstancia.createStatement()) {
            sentencia.execute("SELECT pg_advisory_lock(" + PublicadorOutbox.BLOQUEO_PUBLICADOR + ")");

            assertThat(publicador.publicarPendientes()).isZero();
            verify(kafka, never()).send(anyString(), anyString(), any());

            sentencia.execute("SELECT pg_advisory_unlock(" + PublicadorOutbox.BLOQUEO_PUBLICADOR + ")");
        }

        assertThat(publicador.publicarPendientes()).isEqualTo(1);
    }

    @Test
    void laOrdenYSuComandoSeGuardanJuntosOFallanJuntos() {
        ComandoOrden comando = ComandoOrden.nueva(USUARIO, "ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 10);

        assertThatThrownBy(() -> transaccion.executeWithoutResult(estado -> {
            ordenes.save(Orden.nueva(comando, UUID.fromString(USUARIO), "clave-" + UUID.randomUUID(), Instant.now().truncatedTo(ChronoUnit.MICROS)));
            outbox.guardar(Topicos.ORDERS_COMMANDS, "ANDX", comando);
            throw new IllegalStateException("algo falla antes del commit");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(ordenes.count()).isZero();
        assertThat(pendientes()).isZero();
    }

    @Test
    void elOutboxNoSePuedeUsarFueraDeUnaTransaccion() {
        ComandoOrden comando = ComandoOrden.nueva(USUARIO, "ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 10);

        assertThatThrownBy(() -> outbox.guardar(Topicos.ORDERS_COMMANDS, "ANDX", comando))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(pendientes()).isZero();
    }

    @Test
    void laLimpiezaBorraSoloLosPublicadosViejos() {
        guardar("ANDX");
        guardar("CAFE");
        guardar("FLOR");
        jdbc.sql("UPDATE outbox SET publicado_en = now() - interval '8 days' WHERE clave = 'ANDX'").update();
        jdbc.sql("UPDATE outbox SET publicado_en = now() - interval '1 day' WHERE clave = 'CAFE'").update();

        assertThat(publicador.borrarPublicadosAntesDe(Instant.now().minus(7, ChronoUnit.DAYS))).isEqualTo(1);
        assertThat(jdbc.sql("SELECT clave FROM outbox ORDER BY clave").query(String.class).list())
                .containsExactly("CAFE", "FLOR");
    }

    /** Guarda un comando en el outbox, como lo hace ServicioOrdenes al crear una orden. */
    private ComandoOrden guardar(String simbolo) {
        ComandoOrden comando = ComandoOrden.nueva(USUARIO, simbolo, Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 10);
        transaccion.executeWithoutResult(estado -> outbox.guardar(Topicos.ORDERS_COMMANDS, simbolo, comando));
        return comando;
    }

    private int pendientes() {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE publicado_en IS NULL").query(Integer.class).single();
    }

    private static CompletableFuture<SendResult<String, Object>> confirmado(String topico) {
        RecordMetadata meta = new RecordMetadata(new TopicPartition(topico, 0), 0, 0, 0, 0, 0);
        return CompletableFuture.completedFuture(new SendResult<>(new ProducerRecord<>(topico, null), meta));
    }
}
