package co.bolsasim.portfolio.servicio;

import static org.assertj.core.api.Assertions.assertThat;

import co.bolsasim.eventos.EstadoInstrumento;
import co.bolsasim.eventos.InstrumentoActualizado;
import co.bolsasim.portfolio.cuenta.RepositorioCuentas;
import co.bolsasim.portfolio.posicion.RepositorioPosiciones;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Prueba de integración de la oferta inicial que llega antes que el creador de mercado (RF-59), contra PostgreSQL
 * real (RNF-36): portfolio-service lee instruments.events desde el principio, y el users.registered del creador
 * puede llegar después. En su propia base, porque en las demás pruebas el creador ya existe.
 *
 * <p>Necesita Docker. Si Docker está cerrado, la prueba se salta en vez de fallar.
 */
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "bolsasim.outbox.programado=false"})
@Testcontainers(disabledWithoutDocker = true)
class EmisionPendienteIntegracionTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private ServicioEmisiones emisiones;

    @Autowired
    private ServicioCuentas servicioCuentas;

    @Autowired
    private RepositorioCuentas cuentas;

    @Autowired
    private RepositorioPosiciones posiciones;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void sinCuentaDelCreadorLaEmisionQuedaPendienteYSeAplicaCuandoLlegaSinPerderNiDuplicarNada() {
        emisiones.emitir(instrumento("ANDX", 1_000_000, 5_000));
        emisiones.emitir(instrumento("CAFE", 500_000, 12_000));
        // Un evento repetido mientras espera tampoco la duplica
        assertThat(emisiones.emitir(instrumento("ANDX", 1_000_000, 5_000))).isFalse();

        assertThat(estados()).isEqualTo("ANDX:PENDIENTE,CAFE:PENDIENTE");
        assertThat(jdbc.sql("SELECT count(*) FROM posicion").query(Integer.class).single()).isZero();

        // Llega el users.registered del creador: recibe las dos emisiones
        UUID creador = UUID.randomUUID();
        servicioCuentas.abrir(creador, "BOT");
        assertThat(emisiones.registrarCreador(creador)).isTrue();

        UUID cuenta = cuentas.creadorDeMercado().orElseThrow();
        assertThat(estados()).isEqualTo("ANDX:APLICADA,CAFE:APLICADA");
        assertThat(posiciones.buscar(cuenta, "ANDX").orElseThrow().cantidad()).isEqualTo(1_000_000);
        assertThat(posiciones.buscar(cuenta, "CAFE").orElseThrow().costoPromedio()).isEqualByComparingTo("12000");

        // Su evento repetido (auth-service lo publica en cada arranque) no emite otra vez
        emisiones.registrarCreador(creador);
        assertThat(posiciones.buscar(cuenta, "ANDX").orElseThrow().cantidad()).isEqualTo(1_000_000);

        // Un instrumento que llega después ya va directo al creador
        emisiones.emitir(instrumento("FLOR", 3_000_000, 1_500));
        assertThat(posiciones.buscar(cuenta, "FLOR").orElseThrow().cantidad()).isEqualTo(3_000_000);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM movimiento
                        WHERE tipo = 'EMISION' AND simbolo IN ('ANDX', 'CAFE', 'FLOR')""")
                .query(Integer.class).single()).isEqualTo(3);
    }

    @Test
    void unaEmisionDeMasDeDosMilMillonesDeAccionesCabe() {
        emisiones.emitir(instrumento("GRAN", 3_000_000_000L, 100));

        assertThat(jdbc.sql("SELECT acciones_emitidas FROM emision WHERE simbolo = 'GRAN'").query(Long.class)
                .single()).isEqualTo(3_000_000_000L);
    }

    private String estados() {
        return String.join(",", jdbc.sql("""
                        SELECT simbolo || ':' || estado FROM emision WHERE simbolo IN ('ANDX', 'CAFE')
                        ORDER BY simbolo""")
                .query(String.class).list());
    }

    private static InstrumentoActualizado instrumento(String simbolo, long emitidas, long precio) {
        return new InstrumentoActualizado(simbolo, "Empresa " + simbolo, "Pruebas", precio, 10, emitidas,
                EstadoInstrumento.ACTIVO, Instant.now());
    }
}
