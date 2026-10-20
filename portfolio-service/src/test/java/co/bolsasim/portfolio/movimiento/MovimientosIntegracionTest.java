package co.bolsasim.portfolio.movimiento;

import static org.assertj.core.api.Assertions.assertThat;

import co.bolsasim.portfolio.cuenta.Cuenta;
import co.bolsasim.portfolio.servicio.PaginaMovimientos;
import co.bolsasim.portfolio.servicio.ServicioCuentas;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Prueba de integración del historial de movimientos contra PostgreSQL real (RNF-36): filtros por tipo y por
 * fechas, paginación por cursor, y EXPLAIN de cada consulta con 20.000 movimientos. Por ahora el único
 * movimiento real es SALDO_INICIAL: los demás se insertan como datos de prueba. Kafka no hace falta.
 *
 * <p>Necesita Docker. Si Docker está cerrado, la prueba se salta en vez de fallar.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers(disabledWithoutDocker = true)
class MovimientosIntegracionTest {

    private static final UUID ANA = UUID.randomUUID();
    private static final UUID BETO = UUID.randomUUID();
    private static final UUID BOT = UUID.randomUUID();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private ServicioCuentas servicio;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    private Cuenta deAna;

    @BeforeEach
    void movimientosDeAnaYDeBeto() {
        jdbc.sql("DELETE FROM movimiento").update();
        jdbc.sql("DELETE FROM cuenta").update();
        deAna = servicio.abrir(ANA);
        Cuenta deBeto = servicio.abrir(BETO);
        // 40 de Ana entre el 1 y el 20 de octubre (hora de Colombia), con tipos repartidos; de a 2 comparten la
        // misma fecha, así el id decide el orden. Y 10 de Beto
        insertar(deAna.id(), 40, "2026-10-20 23:00:00-05");
        insertar(deBeto.id(), 10, "2026-10-20 23:00:00-05");
    }

    @Test
    void recorrerTodasLasPaginasDevuelveCadaMovimientoUnaVezYEnOrden() {
        List<UUID> recorridos = recorrer(null, null, null, 7);

        assertThat(recorridos).hasSize(41).doesNotHaveDuplicates().isEqualTo(enOrden(""));
    }

    @Test
    void filtrarPorTipoDevuelveSoloEseTipo() {
        List<UUID> compras = recorrer("COMPRA", null, null, 4);

        assertThat(compras).isNotEmpty().isEqualTo(enOrden(" AND tipo = 'COMPRA'"));
    }

    @Test
    void elRangoDeFechasIncluyeLosDosDiasCompletosEnHoraDeColombia() {
        List<UUID> delRango = recorrer(null, "2026-10-10", "2026-10-12", 5);

        assertThat(delRango).isNotEmpty().isEqualTo(enOrden(
                " AND creado_en >= '2026-10-10 00:00:00-05' AND creado_en < '2026-10-13 00:00:00-05'"));
    }

    @Test
    void nadieVeLosMovimientosDeOtro() {
        PaginaMovimientos deBeto = servicio.movimientos(BETO, "INVERSIONISTA", null, null, null, 100, null);

        assertThat(deBeto.movimientos()).hasSize(11)
                .allSatisfy(movimiento -> assertThat(movimiento.cuentaId()).isNotEqualTo(deAna.id()));
    }

    /**
     * Con 20.000 movimientos de una cuenta, cada consulta debe saltar en su índice y leer las filas ya ordenadas:
     * ni Seq Scan ni Sort, con el plan para esos valores y con el plan genérico de la consulta preparada.
     */
    @ParameterizedTest
    @CsvSource({
            "false, false, false, movimiento_cuenta_creado",
            "false, true,  true,  movimiento_cuenta_creado",
            "true,  false, true,  movimiento_cuenta_tipo_creado",
            "true,  true,  false, movimiento_cuenta_tipo_creado"})
    void cadaConsultaUsaSuIndiceSinOrdenarEnMemoria(boolean conTipo, boolean conFechas, boolean conCursor,
                                                    String indice) throws SQLException {
        Cuenta delBot = servicio.abrir(BOT);
        insertar(delBot.id(), 20_000, "2026-10-20 23:00:00-05");
        jdbc.sql("ANALYZE movimiento").update();

        for (String modo : List.of("force_custom_plan", "force_generic_plan")) {
            String plan = explicar(delBot.id(), conTipo, conFechas, conCursor, modo);

            assertThat(plan).as("plan %s:%n%s", modo, plan)
                    .contains(indice)
                    .doesNotContain("Seq Scan", "Sort");
        }
    }

    private List<UUID> recorrer(String tipo, String desde, String hasta, int tamano) {
        List<UUID> ids = new ArrayList<>();
        String despues = null;
        do {
            PaginaMovimientos pagina = servicio.movimientos(ANA, "INVERSIONISTA", tipo, desde, hasta, tamano, despues);
            assertThat(pagina.movimientos()).hasSizeLessThanOrEqualTo(tamano);
            pagina.movimientos().forEach(movimiento -> ids.add(movimiento.id()));
            despues = pagina.siguiente() == null ? null : pagina.siguiente().codificar();
        } while (despues != null);
        return ids;
    }

    private List<UUID> enOrden(String condicion) {
        return jdbc.sql("SELECT id FROM movimiento WHERE cuenta_id = :cuenta" + condicion
                        + " ORDER BY creado_en DESC, id DESC")
                .param("cuenta", deAna.id())
                .query(UUID.class)
                .list();
    }

    /** Movimientos de prueba, cada 12 horas hacia atrás desde esa fecha, con los tipos repartidos. */
    private void insertar(UUID cuentaId, int cuantos, String desde) {
        jdbc.sql("""
                        INSERT INTO movimiento (id, cuenta_id, tipo, monto, simbolo, cantidad, referencia_id, creado_en)
                        SELECT gen_random_uuid(), :cuenta,
                               (ARRAY['RESERVA', 'LIBERACION', 'COMPRA', 'VENTA'])[1 + i % 4],
                               (CASE WHEN i % 2 = 0 THEN -1 ELSE 1 END) * 50000, 'ANDX', 10, gen_random_uuid(),
                               CAST(:desde AS timestamptz) - (i / 2) * interval '12 hours'
                        FROM generate_series(1, :cuantos) AS i""")
                .param("cuenta", cuentaId)
                .param("desde", desde)
                .param("cuantos", cuantos)
                .update();
    }

    /** EXPLAIN de la consulta como sentencia preparada, como la envía la aplicación. */
    private String explicar(UUID cuentaId, boolean conTipo, boolean conFechas, boolean conCursor, String modo)
            throws SQLException {
        String sql = RegistroMovimientos.sql(conTipo, conFechas, conFechas, conCursor);
        Map<String, String[]> parametros = new LinkedHashMap<>(); // nombre -> {tipo, valor}
        parametros.put("cuenta", new String[] {"uuid", "'" + cuentaId + "'"});
        if (conTipo) {
            parametros.put("tipo", new String[] {"varchar", "'COMPRA'"});
        }
        if (conFechas) {
            parametros.put("desde", new String[] {"timestamptz", "'2026-01-01 00:00:00-05'"});
            parametros.put("hasta", new String[] {"timestamptz", "'2026-10-21 00:00:00-05'"});
        }
        if (conCursor) {
            parametros.put("creadoEn", new String[] {"timestamptz", "'2026-10-15 00:00:00-05'"});
            parametros.put("id", new String[] {"uuid", "'" + UUID.randomUUID() + "'"});
        }
        parametros.put("limite", new String[] {"integer", "21"});

        List<String> tipos = new ArrayList<>();
        List<String> valores = new ArrayList<>();
        int numero = 1;
        for (var parametro : parametros.entrySet()) {
            sql = sql.replace(":" + parametro.getKey(), "$" + numero++);
            tipos.add(parametro.getValue()[0]);
            valores.add(parametro.getValue()[1]);
        }

        try (Connection conexion = dataSource.getConnection(); Statement sentencia = conexion.createStatement()) {
            sentencia.execute("SET plan_cache_mode = " + modo);
            sentencia.execute("PREPARE consulta(" + String.join(", ", tipos) + ") AS " + sql);
            try (ResultSet plan = sentencia.executeQuery("EXPLAIN EXECUTE consulta(" + String.join(", ", valores) + ")")) {
                StringBuilder texto = new StringBuilder();
                while (plan.next()) {
                    texto.append(plan.getString(1)).append('\n');
                }
                return texto.toString();
            } finally {
                sentencia.execute("DEALLOCATE consulta");
                sentencia.execute("RESET plan_cache_mode");
            }
        }
    }
}
