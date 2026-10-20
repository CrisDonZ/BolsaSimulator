package co.bolsasim.portfolio.kafka;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tabla EVENTO_PROCESADO (regla 6, RNF-09): los eventos de Kafka que ya se aplicaron. Se marca en la misma
 * transacción que el cambio: si el cambio se deshace, la marca también, y el evento se puede aplicar después.
 */
@Repository
public class EventosProcesados {

    private final JdbcClient jdbc;

    public EventosProcesados(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true si es la primera vez que llega; false si ya se había aplicado */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean marcar(UUID eventoId) {
        return jdbc.sql("""
                        INSERT INTO evento_procesado (id_evento, procesado_en) VALUES (:id, :ahora)
                        ON CONFLICT (id_evento) DO NOTHING""")
                .param("id", eventoId)
                .param("ahora", Instant.now().atOffset(ZoneOffset.UTC))
                .update() == 1;
    }
}
