package co.bolsasim.order.orden;

import static org.assertj.core.api.Assertions.assertThat;

import co.bolsasim.order.servicio.PaginaOrdenes;
import co.bolsasim.order.servicio.ServicioOrdenes;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
 * Prueba de integración de la lista paginada contra PostgreSQL real (RNF-36): recorre todas las páginas,
 * comprueba los filtros y que nadie ve las órdenes de otro, y revisa con EXPLAIN que cada consulta usa su
 * índice y no ordena en memoria, aunque el usuario tenga 20.000 órdenes. Kafka no hace falta: los listeners
 * y el outbox están apagados.
 *
 * <p>Necesita Docker. Si Docker está cerrado, la prueba se salta en vez de fallar.
 */
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "bolsasim.outbox.programado=false"})
@Testcontainers(disabledWithoutDocker = true)
class ConsultaOrdenesIntegracionTest {

    private static final UUID ANA = UUID.randomUUID();
    private static final UUID BETO = UUID.randomUUID();
    private static final UUID BOT = UUID.randomUUID();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private ServicioOrdenes servicio;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void ordenesDeAnaYDeBeto() {
        jdbc.sql("DELETE FROM orden").update();
        // 45 de Ana y 10 de Beto, con los 6 estados repartidos. De a 3 comparten la misma fecha: el id
        // decide el orden entre ellas, y el cursor no debe repetir ni saltarse ninguna
        insertar(ANA, 45);
        insertar(BETO, 10);
    }

    @Test
    void recorrerTodasLasPaginasDevuelveCadaOrdenUnaVezYEnOrden() {
        List<UUID> recorridas = recorrer(ANA, null, 7);

        assertThat(recorridas).hasSize(45).doesNotHaveDuplicates().isEqualTo(enOrden(ANA, ""));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "ABIERTAS  | estado IN ('ABIERTA', 'PARCIAL')",
            "EJECUTADA | estado = 'EJECUTADA'",
            "pendiente | estado = 'PENDIENTE'"})
    void elFiltroDevuelveSoloEsasOrdenes(String filtro, String condicion) {
        List<UUID> recorridas = recorrer(ANA, filtro, 4);

        assertThat(recorridas).isNotEmpty().doesNotHaveDuplicates().isEqualTo(enOrden(ANA, " AND " + condicion));
    }

    @Test
    void nadieVeLasOrdenesDeOtro() {
        assertThat(recorrer(BETO, null, 100)).hasSize(10).doesNotContainAnyElementsOf(enOrden(ANA, ""));
    }

    @Test
    void unaOrdenNuevaNoCorreLasPaginasSiguientes() {
        PaginaOrdenes primera = servicio.listar(ANA.toString(), null, 10, null);
        // Ana envía otra orden mientras mira la primera página
        jdbc.sql("""
                        INSERT INTO orden (id, usuario_id, simbolo, lado, tipo, precio_limite, cantidad, cantidad_ejecutada,
                                           estado, creada_en, actualizada_en, version)
                        VALUES (gen_random_uuid(), :usuario, 'ANDX', 'COMPRA', 'LIMITE', 5000, 10, 0, 'PENDIENTE',
                                now(), now(), 0)""")
                .param("usuario", ANA)
                .update();

        PaginaOrdenes segunda = servicio.listar(ANA.toString(), null, 10, primera.siguiente().codificar());

        // La segunda página empieza justo donde quedó la primera, sin repetir la última
        assertThat(segunda.ordenes().getFirst().getId()).isEqualTo(enOrden(ANA, "").get(11));
    }

    /**
     * Con 20.000 órdenes de un bot, cada consulta debe saltar en su índice a la página pedida y leer las filas
     * ya ordenadas: ni Seq Scan (leer toda la tabla) ni Sort (ordenar en memoria). Se revisa con el plan que
     * PostgreSQL arma para esos valores y con el plan genérico que usa cuando reutiliza la consulta preparada.
     */
    @ParameterizedTest
    @CsvSource({
            "TODAS,     false, orden_usuario_creada",
            "TODAS,     true,  orden_usuario_creada",
            "ABIERTAS,  false, orden_usuario_abiertas",
            "ABIERTAS,  true,  orden_usuario_abiertas",
            "EJECUTADA, false, orden_usuario_estado_creada",
            "EJECUTADA, true,  orden_usuario_estado_creada"})
    void cadaConsultaUsaSuIndiceSinOrdenarEnMemoria(String estado, boolean conCursor, String indice) throws SQLException {
        insertar(BOT, 20_000);
        jdbc.sql("ANALYZE orden").update();
        FiltroEstado filtro = estado.equals("TODAS") ? FiltroEstado.TODAS : FiltroEstado.de(estado);

        for (String modo : List.of("force_custom_plan", "force_generic_plan")) {
            String plan = explicar(ConsultaOrdenes.sql(filtro, conCursor), filtro, conCursor, modo);

            assertThat(plan).as("plan %s:%n%s", modo, plan)
                    .contains("Index Scan using " + indice)
                    .doesNotContain("Seq Scan", "Sort");
        }
    }

    private List<UUID> recorrer(UUID usuario, String estado, int tamano) {
        List<UUID> ids = new ArrayList<>();
        String despues = null;
        do {
            PaginaOrdenes pagina = servicio.listar(usuario.toString(), estado, tamano, despues);
            assertThat(pagina.ordenes()).hasSizeLessThanOrEqualTo(tamano);
            pagina.ordenes().forEach(orden -> ids.add(orden.getId()));
            despues = pagina.siguiente() == null ? null : pagina.siguiente().codificar();
        } while (despues != null);
        return ids;
    }

    /** Las órdenes del usuario en el orden de la lista, leídas directo de la tabla. */
    private List<UUID> enOrden(UUID usuario, String condicion) {
        return jdbc.sql("SELECT id FROM orden WHERE usuario_id = :usuario" + condicion
                        + " ORDER BY creada_en DESC, id DESC")
                .param("usuario", usuario)
                .query(UUID.class)
                .list();
    }

    private void insertar(UUID usuario, int cuantas) {
        jdbc.sql("""
                        INSERT INTO orden (id, usuario_id, simbolo, lado, tipo, precio_limite, cantidad, cantidad_ejecutada,
                                           estado, creada_en, actualizada_en, version)
                        SELECT gen_random_uuid(), :usuario, 'ANDX', 'COMPRA', 'LIMITE', 5000, 10, 0,
                               (ARRAY['PENDIENTE', 'ABIERTA', 'PARCIAL', 'EJECUTADA', 'CANCELADA', 'RECHAZADA'])[1 + i % 6],
                               :base - (i / 3) * interval '1 second', :base, 0
                        FROM generate_series(1, :cuantas) AS i""")
                .param("usuario", usuario)
                .param("base", Instant.now().minus(1, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC))
                .param("cuantas", cuantas)
                .update();
    }

    /**
     * EXPLAIN de la consulta como sentencia preparada, igual que la envía la aplicación, en una sola conexión.
     * Los parámetros con nombre se vuelven $1, $2...
     */
    private String explicar(String sql, FiltroEstado filtro, boolean conCursor, String modo) throws SQLException {
        Map<String, String[]> parametros = new LinkedHashMap<>(); // nombre -> {tipo, valor}
        parametros.put("usuario", new String[] {"uuid", "'" + BOT + "'"});
        if (filtro.estado() != null) {
            parametros.put("estado", new String[] {"varchar", "'" + filtro.estado().name() + "'"});
        }
        if (conCursor) {
            parametros.put("creadaEn", new String[] {"timestamptz", "now() - interval '2 hours'"});
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
