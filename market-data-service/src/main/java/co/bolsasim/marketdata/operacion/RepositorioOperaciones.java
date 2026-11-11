package co.bolsasim.marketdata.operacion;

import co.bolsasim.eventos.EstadoInstrumento;
import co.bolsasim.eventos.OperacionEjecutada;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Tabla OPERACION, con JDBC: guarda las operaciones de trades.executed y saca de ellas los precios del día de cada
 * instrumento.
 */
@Repository
public class RepositorioOperaciones {

    /**
     * Cada instrumento con su último precio y su precio de apertura, en una sola consulta: por cada instrumento
     * busca en el índice (simbolo, ejecutada_en) su última operación y la última anterior al comienzo del día.
     * Si dos operaciones tienen la misma hora, manda la que llegó después (secuencia).
     */
    private static final String COTIZACIONES = """
            SELECT i.simbolo, i.nombre, i.sector, i.precio_inicial, i.tick, i.acciones_emitidas, i.estado, i.creado_en,
                   COALESCE(ultima.precio, i.precio_inicial) AS ultimo_precio,
                   COALESCE(apertura.precio, i.precio_inicial) AS precio_apertura
            FROM instrumento i
            LEFT JOIN LATERAL (SELECT o.precio FROM operacion o
                               WHERE o.simbolo = i.simbolo
                               ORDER BY o.ejecutada_en DESC, o.secuencia DESC LIMIT 1) ultima ON true
            LEFT JOIN LATERAL (SELECT o.precio FROM operacion o
                               WHERE o.simbolo = i.simbolo AND o.ejecutada_en < :inicio
                               ORDER BY o.ejecutada_en DESC, o.secuencia DESC LIMIT 1) apertura ON true
            """;

    private final JdbcClient jdbc;

    public RepositorioOperaciones(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Guarda una operación con la siguiente secuencia de su símbolo, en un solo INSERT. Las operaciones de un
     * símbolo llegan por una sola partición y las lee un solo consumidor, así que dos no piden la misma secuencia
     * a la vez; si pasara (un rebalanceo), la restricción UNIQUE hace fallar a la segunda y Kafka la reintenta.
     *
     * @return false si no guardó nada: la operación ya estaba (evento repetido) o su símbolo no es un instrumento
     */
    public boolean guardar(OperacionEjecutada operacion) {
        return jdbc.sql("""
                        INSERT INTO operacion (id, simbolo, secuencia, precio, cantidad, orden_compra_id,
                                               orden_venta_id, ejecutada_en)
                        SELECT :id, i.simbolo,
                               (SELECT COALESCE(MAX(o.secuencia), 0) + 1 FROM operacion o WHERE o.simbolo = i.simbolo),
                               :precio, :cantidad, :compra, :venta, :en
                        FROM instrumento i
                        WHERE i.simbolo = :simbolo
                        ON CONFLICT (id) DO NOTHING""")
                .param("id", operacion.operacionId())
                .param("simbolo", operacion.simbolo())
                .param("precio", operacion.precio())
                .param("cantidad", operacion.cantidad())
                .param("compra", operacion.ordenCompraId())
                .param("venta", operacion.ordenVentaId())
                .param("en", operacion.ejecutadaEn().atOffset(ZoneOffset.UTC))
                .update() == 1;
    }

    /**
     * Todos los instrumentos, por símbolo, con sus precios del día.
     *
     * @param inicioDelDia las operaciones anteriores a este momento son de días pasados
     */
    public List<Cotizacion> cotizaciones(Instant inicioDelDia) {
        return jdbc.sql(COTIZACIONES + "ORDER BY i.simbolo")
                .param("inicio", inicioDelDia.atOffset(ZoneOffset.UTC))
                .query(RepositorioOperaciones::cotizacion)
                .list();
    }

    public Optional<Cotizacion> cotizacion(String simbolo, Instant inicioDelDia) {
        return jdbc.sql(COTIZACIONES + "WHERE i.simbolo = :simbolo")
                .param("inicio", inicioDelDia.atOffset(ZoneOffset.UTC))
                .param("simbolo", simbolo)
                .query(RepositorioOperaciones::cotizacion)
                .optional();
    }

    private static Cotizacion cotizacion(ResultSet fila, int numero) throws SQLException {
        return new Cotizacion(fila.getString("simbolo"), fila.getString("nombre"), fila.getString("sector"),
                fila.getLong("precio_inicial"), fila.getLong("tick"), fila.getLong("acciones_emitidas"),
                EstadoInstrumento.valueOf(fila.getString("estado")),
                fila.getObject("creado_en", OffsetDateTime.class).toInstant(),
                fila.getLong("ultimo_precio"), fila.getLong("precio_apertura"));
    }
}
