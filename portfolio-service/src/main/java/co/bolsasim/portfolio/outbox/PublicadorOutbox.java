package co.bolsasim.portfolio.outbox;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Envía a Kafka los eventos pendientes del outbox, en el orden en que se guardaron, y los marca publicados
 * solo cuando Kafka los confirma (acks=all). Si Kafka está caído, las filas esperan y salen en la primera
 * ronda después de que vuelva. Es una copia del de order-service, con su propio advisory lock.
 *
 * <p>Si un envío falla, la ronda se detiene ahí y solo se marcan los anteriores: así la respuesta de una reserva
 * nunca adelanta a otra del mismo usuario que quedó pendiente (RNF-11).
 *
 * <p>Con varias instancias de portfolio-service, cada ronda empieza pidiendo un advisory lock de PostgreSQL; solo
 * la instancia que lo obtiene publica y las demás se saltan la ronda. Con FOR UPDATE SKIP LOCKED publicarían
 * a la vez y dos respuestas del mismo usuario podrían llegar en desorden.
 *
 * <p>Entrega al menos una vez, no exactamente una: si portfolio-service se cae entre la confirmación de Kafka y
 * la marca de publicado_en, el evento sale otra vez en la siguiente ronda. Quien lo consume debe reconocer
 * un evento repetido (RNF-09).
 */
@Component
public class PublicadorOutbox {

    private static final Logger log = LoggerFactory.getLogger(PublicadorOutbox.class);

    /** Número fijo que identifica el advisory lock del publicador; cualquier otro número sería igual de válido. */
    static final long BLOQUEO_PUBLICADOR = 2_026_102_004L;

    private static final int LOTE = 100;
    private static final String PAQUETE_EVENTOS = "co.bolsasim.eventos.";

    // Más que delivery.timeout.ms del productor (application.yml): Kafka siempre responde antes
    private static final Duration ESPERA_CONFIRMACION = Duration.ofSeconds(15);

    private final JdbcClient jdbc;
    private final TransactionTemplate transaccion;
    private final KafkaTemplate<String, Object> kafka;
    private final JsonMapper json;

    public PublicadorOutbox(JdbcClient jdbc, TransactionTemplate transaccion, KafkaTemplate<String, Object> kafka,
                            JsonMapper json) {
        this.jdbc = jdbc;
        this.transaccion = transaccion;
        this.kafka = kafka;
        this.json = json;
    }

    /** @return cuántos eventos quedaron publicados en esta ronda */
    public int publicarPendientes() {
        Integer publicados = transaccion.execute(estado -> publicarConElBloqueo());
        return publicados == null ? 0 : publicados;
    }

    /** Borra los eventos publicados antes de esa fecha: no son historia del negocio (la reserva y sus movimientos sí). */
    public int borrarPublicadosAntesDe(Instant limite) {
        return jdbc.sql("DELETE FROM outbox WHERE publicado_en < :limite")
                .param("limite", limite.atOffset(ZoneOffset.UTC))
                .update();
    }

    private int publicarConElBloqueo() {
        // _xact_: el bloqueo se suelta solo al terminar la transacción, aunque algo falle a mitad de camino
        Boolean esMiTurno = jdbc.sql("SELECT pg_try_advisory_xact_lock(:bloqueo)")
                .param("bloqueo", BLOQUEO_PUBLICADOR)
                .query(Boolean.class)
                .single();
        if (!Boolean.TRUE.equals(esMiTurno)) {
            return 0;
        }

        List<Pendiente> pendientes = jdbc.sql("""
                        SELECT id, topico, clave, tipo_evento, CAST(payload AS text) AS payload
                        FROM outbox
                        WHERE publicado_en IS NULL
                        ORDER BY creado_en, id
                        LIMIT :lote""")
                .param("lote", LOTE)
                .query(Pendiente.class)
                .list();
        if (pendientes.isEmpty()) {
            return 0;
        }

        List<UUID> publicados = enviarEnOrden(pendientes);
        if (!publicados.isEmpty()) {
            jdbc.sql("UPDATE outbox SET publicado_en = :ahora WHERE id IN (:ids)")
                    .param("ahora", Instant.now().atOffset(ZoneOffset.UTC))
                    .param("ids", publicados)
                    .update();
        }
        return publicados.size();
    }

    /**
     * Envía todo el lote sin esperar uno por uno (Kafka los agrupa) y después revisa las confirmaciones en
     * orden. Devuelve los ids confirmados hasta la primera falla.
     */
    private List<UUID> enviarEnOrden(List<Pendiente> pendientes) {
        List<CompletableFuture<SendResult<String, Object>>> envios = new ArrayList<>();
        try {
            for (Pendiente pendiente : pendientes) {
                envios.add(kafka.send(pendiente.topico(), pendiente.clave(), evento(pendiente)));
            }
        } catch (RuntimeException e) {
            // Kafka puede fallar al enviar, no solo al confirmar (por ejemplo, si no responde en max.block.ms):
            // lo que ya salió se revisa abajo y el resto espera a la siguiente ronda
            log.warn("No se pudo enviar el evento {} del outbox; se reintenta en la siguiente ronda: {}",
                    pendientes.get(envios.size()).id(), e.getMessage());
        }

        List<UUID> publicados = new ArrayList<>();
        for (int i = 0; i < envios.size(); i++) {
            Pendiente pendiente = pendientes.get(i);
            try {
                RecordMetadata meta = envios.get(i).get(ESPERA_CONFIRMACION.toMillis(), TimeUnit.MILLISECONDS)
                        .getRecordMetadata();
                publicados.add(pendiente.id());
                log.info("Evento {} del outbox publicado en {} (clave {}, partición {}, offset {})",
                        pendiente.id(), meta.topic(), pendiente.clave(), meta.partition(), meta.offset());
            } catch (ExecutionException | TimeoutException e) {
                log.warn("Kafka no confirmó el evento {} del outbox; se reintenta en la siguiente ronda: {}",
                        pendiente.id(), e.getMessage());
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return publicados;
    }

    /**
     * Vuelve a armar el objeto original para que el mensaje salga idéntico al de antes del outbox: el mismo
     * JSON y el mismo header de tipo que el consumidor usa para leerlo.
     */
    private Object evento(Pendiente pendiente) {
        // Solo clases de eventos: el tipo viene de la base y no debe poder instanciar cualquier clase
        if (!pendiente.tipoEvento().startsWith(PAQUETE_EVENTOS)) {
            throw new IllegalStateException("Tipo de evento no permitido en el outbox: " + pendiente.tipoEvento());
        }
        try {
            return json.readValue(pendiente.payload(), Class.forName(pendiente.tipoEvento()));
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("No existe la clase del evento " + pendiente.tipoEvento(), e);
        }
    }

    /** Una fila del outbox que todavía no sale. */
    record Pendiente(UUID id, String topico, String clave, String tipoEvento, String payload) {
    }
}
