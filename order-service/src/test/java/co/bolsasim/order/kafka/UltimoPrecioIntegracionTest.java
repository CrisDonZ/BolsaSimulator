package co.bolsasim.order.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import co.bolsasim.eventos.EstadoInstrumento;
import co.bolsasim.eventos.InstrumentoActualizado;
import co.bolsasim.eventos.OperacionEjecutada;
import co.bolsasim.order.instrumento.Instrumento;
import co.bolsasim.order.instrumento.RepositorioInstrumentos;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Prueba de integración del último precio contra PostgreSQL real (RNF-36): trades.executed lo cambia, la misma
 * operación dos veces deja lo mismo y una más vieja no lo reemplaza. Las operaciones se aplican llamando al listener
 * directamente; Kafka no hace falta.
 *
 * <p>Necesita Docker. Si Docker está cerrado, la prueba se salta en vez de fallar.
 */
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "bolsasim.outbox.programado=false"})
@Testcontainers(disabledWithoutDocker = true)
class UltimoPrecioIntegracionTest {

    private static final Instant AHORA = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private OperacionEjecutadaListener listener;

    @Autowired
    private RepositorioInstrumentos instrumentos;

    @BeforeEach
    void andxSinOperaciones() {
        instrumentos.deleteAll();
        instrumentos.save(new Instrumento(new InstrumentoActualizado("ANDX", "Andex Energía", "Energía", 5_000, 10,
                1_000_000, EstadoInstrumento.ACTIVO, AHORA)));
    }

    @Test
    void sinOperacionesLaReferenciaEsElPrecioInicialYDespuesElDeLaUltimaOperacion() {
        assertThat(andx().precioDeReferencia()).contains(5_000L);

        listener.recibir(operacion(4_900, AHORA));

        assertThat(andx().getUltimoPrecio()).isEqualTo(4_900L);
        assertThat(andx().precioDeReferencia()).contains(4_900L);
    }

    @Test
    void laMismaOperacionDosVecesDejaLoMismoYUnaMasViejaNoLaReemplaza() {
        OperacionEjecutada nueva = operacion(5_100, AHORA);

        listener.recibir(nueva);
        listener.recibir(nueva);
        listener.recibir(operacion(4_800, AHORA.minusSeconds(1)));

        assertThat(andx().getUltimoPrecio()).isEqualTo(5_100L);
    }

    @Test
    void unEventoDelInstrumentoNoBorraElUltimoPrecio() {
        listener.recibir(operacion(4_900, AHORA));

        Instrumento instrumento = andx();
        instrumento.actualizar(new InstrumentoActualizado("ANDX", "Andex Energía S.A.", "Energía", 5_000, 10,
                1_000_000, EstadoInstrumento.SUSPENDIDO, AHORA.plusSeconds(1)));
        instrumentos.save(instrumento);

        assertThat(andx().getUltimoPrecio()).isEqualTo(4_900L);
    }

    private Instrumento andx() {
        return instrumentos.findById("ANDX").orElseThrow();
    }

    private static OperacionEjecutada operacion(long precio, Instant ejecutadaEn) {
        return new OperacionEjecutada(UUID.randomUUID(), "ANDX", precio, 10, UUID.randomUUID(),
                UUID.randomUUID().toString(), UUID.randomUUID(), UUID.randomUUID().toString(), ejecutadaEn);
    }
}
