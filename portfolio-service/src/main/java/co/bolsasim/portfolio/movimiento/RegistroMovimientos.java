package co.bolsasim.portfolio.movimiento;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Tabla MOVIMIENTO: guarda los movimientos y lee la lista de una cuenta paginada por cursor (keyset), con el
 * mismo enfoque que la lista de órdenes: cada página salta en el índice al punto donde quedó la anterior, sin
 * OFFSET ni COUNT.
 */
@Repository
public class RegistroMovimientos {

    private final JdbcClient jdbc;

    public RegistroMovimientos(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void registrar(UUID cuentaId, TipoMovimiento tipo, long monto, String simbolo, Integer cantidad,
                          UUID referenciaId, Instant creadoEn) {
        jdbc.sql("""
                        INSERT INTO movimiento (id, cuenta_id, tipo, monto, simbolo, cantidad, referencia_id, creado_en)
                        VALUES (:id, :cuenta, :tipo, :monto, :simbolo, :cantidad, :referencia, :creadoEn)""")
                .param("id", UUID.randomUUID())
                .param("cuenta", cuentaId)
                .param("tipo", tipo.name())
                .param("monto", monto)
                .param("simbolo", simbolo)
                .param("cantidad", cantidad)
                .param("referencia", referenciaId)
                // En microsegundos, como los guarda PostgreSQL: es parte del cursor de la paginación
                .param("creadoEn", creadoEn.truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC))
                .update();
    }

    /**
     * @param tipo    solo ese tipo; null para todos
     * @param desde   desde ese momento, incluido; null sin límite
     * @param hasta   hasta ese momento, sin incluirlo; null sin límite
     * @param despues el cursor de la página anterior; null para la primera
     */
    public List<Movimiento> buscar(UUID cuentaId, TipoMovimiento tipo, Instant desde, Instant hasta, Cursor despues,
                                   int limite) {
        var consulta = jdbc.sql(sql(tipo != null, desde != null, hasta != null, despues != null))
                .param("cuenta", cuentaId)
                .param("limite", limite);
        if (tipo != null) {
            consulta = consulta.param("tipo", tipo.name());
        }
        if (desde != null) {
            consulta = consulta.param("desde", desde.atOffset(ZoneOffset.UTC));
        }
        if (hasta != null) {
            consulta = consulta.param("hasta", hasta.atOffset(ZoneOffset.UTC));
        }
        if (despues != null) {
            consulta = consulta.param("creadoEn", despues.creadoEn().atOffset(ZoneOffset.UTC))
                    .param("id", despues.id());
        }
        return consulta.query(Movimiento.class).list();
    }

    /**
     * La consulta para esos filtros, armada con fragmentos fijos: con un "(:tipo IS NULL OR tipo = :tipo)"
     * PostgreSQL no podría elegir el índice del filtro. Ningún fragmento lleva texto de la petición; los valores
     * van como parámetros. Visible para la prueba que revisa con EXPLAIN que usa su índice.
     */
    static String sql(boolean conTipo, boolean conDesde, boolean conHasta, boolean conCursor) {
        List<String> condiciones = new ArrayList<>(List.of("cuenta_id = :cuenta"));
        if (conTipo) {
            condiciones.add("tipo = :tipo");
        }
        if (conDesde) {
            condiciones.add("creado_en >= :desde");
        }
        if (conHasta) {
            condiciones.add("creado_en < :hasta");
        }
        if (conCursor) {
            // "Después de (fecha, id)" en el orden de la lista, en una sola condición que el índice resuelve
            condiciones.add("(creado_en, id) < (:creadoEn, :id)");
        }
        return "SELECT id, cuenta_id, tipo, monto, simbolo, cantidad, referencia_id, creado_en FROM movimiento WHERE "
                + String.join(" AND ", condiciones) + " ORDER BY creado_en DESC, id DESC LIMIT :limite";
    }
}
