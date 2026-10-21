package co.bolsasim.portfolio.outbox;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Cada cuánto corre el outbox: publica los pendientes cada 100 ms (unos 50 ms de demora en promedio, dentro
 * del segundo de RNF-02) y una vez al día borra los eventos publicados hace más de 7 días.
 *
 * <p>Las pruebas que quieren decidir cuándo publicar lo apagan con bolsasim.outbox.programado=false.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "bolsasim.outbox.programado", havingValue = "true", matchIfMissing = true)
public class ProgramadorOutbox {

    private static final Logger log = LoggerFactory.getLogger(ProgramadorOutbox.class);
    private static final Duration CONSERVAR_PUBLICADOS = Duration.ofDays(7);

    private final PublicadorOutbox publicador;

    public ProgramadorOutbox(PublicadorOutbox publicador) {
        this.publicador = publicador;
    }

    /** fixedDelay y no fixedRate: una ronda nunca empieza antes de que termine la anterior. */
    @Scheduled(fixedDelayString = "${bolsasim.outbox.intervalo-ms:100}")
    public void publicar() {
        try {
            publicador.publicarPendientes();
        } catch (RuntimeException e) {
            // Por ejemplo, la base caída: se intenta otra vez en la siguiente ronda
            log.error("Falló una ronda del outbox", e);
        }
    }

    @Scheduled(cron = "${bolsasim.outbox.limpieza:0 30 3 * * *}")
    public void limpiar() {
        int borrados = publicador.borrarPublicadosAntesDe(Instant.now().minus(CONSERVAR_PUBLICADOS));
        log.info("Outbox: {} eventos publicados hace más de 7 días borrados", borrados);
    }
}
