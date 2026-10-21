package co.bolsasim.portfolio.outbox;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Guarda un evento en la tabla OUTBOX, dentro de la transacción del cambio de negocio (regla 7, RNF-12):
 * si la reserva se guarda, su respuesta también; si algo falla, no queda ninguna de las dos. Después
 * {@link PublicadorOutbox} lo envía a Kafka.
 *
 * <p>Es el mismo outbox de order-service: cada servicio tiene el suyo, en su propia base.
 */
@Component
public class Outbox {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public Outbox(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * MANDATORY: llamarlo sin una transacción abierta es un error de programación, porque el evento podría
     * quedar guardado sin el cambio que lo origina.
     *
     * @param clave la clave del mensaje en Kafka, que decide la partición (para las reservas, el usuario)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void guardar(String topico, String clave, Object evento) {
        jdbc.sql("""
                        INSERT INTO outbox (id, topico, clave, tipo_evento, payload, creado_en)
                        VALUES (:id, :topico, :clave, :tipo, CAST(:payload AS jsonb), :creadoEn)""")
                .param("id", UUID.randomUUID())
                .param("topico", topico)
                .param("clave", clave)
                .param("tipo", evento.getClass().getName())
                .param("payload", json.writeValueAsString(evento))
                .param("creadoEn", Instant.now().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC))
                .update();
    }
}
