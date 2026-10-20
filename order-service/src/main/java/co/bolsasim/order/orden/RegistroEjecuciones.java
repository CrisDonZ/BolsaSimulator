package co.bolsasim.order.orden;

import co.bolsasim.eventos.EjecucionOrden;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Tabla EJECUCION: las partes ejecutadas de cada orden y su precio promedio. Con JDBC, como el outbox: participa
 * en la misma transacción que JPA.
 */
@Repository
public class RegistroEjecuciones {

    private final JdbcClient jdbc;

    public RegistroEjecuciones(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return false si esa operación ya estaba registrada para la orden (evento repetido) */
    public boolean registrar(UUID ordenId, EjecucionOrden ejecucion, Instant ejecutadaEn) {
        return jdbc.sql("""
                        INSERT INTO ejecucion (id, orden_id, operacion_id, precio, cantidad, ejecutada_en)
                        VALUES (:id, :orden, :operacion, :precio, :cantidad, :ejecutadaEn)
                        ON CONFLICT (orden_id, operacion_id) DO NOTHING""")
                .param("id", UUID.randomUUID())
                .param("orden", ordenId)
                .param("operacion", ejecucion.operacionId())
                .param("precio", ejecucion.precio())
                .param("cantidad", ejecucion.cantidad())
                .param("ejecutadaEn", ejecutadaEn.atOffset(ZoneOffset.UTC))
                .update() == 1;
    }

    /**
     * Σ(precio × cantidad) / Σ(cantidad) de todas sus ejecuciones, con 4 decimales como NUMERIC(18,4). Se
     * recalcula desde las ejecuciones en vez de acumularlo, así el redondeo de una no se arrastra a la siguiente.
     *
     * @return null si la orden no tiene ejecuciones
     */
    public BigDecimal precioPromedio(UUID ordenId) {
        Totales totales = jdbc.sql("""
                        SELECT COALESCE(SUM(precio * cantidad), 0) AS monto, COALESCE(SUM(cantidad), 0) AS acciones
                        FROM ejecucion WHERE orden_id = :orden""")
                .param("orden", ordenId)
                .query(Totales.class)
                .single();
        if (totales.acciones() == 0) {
            return null;
        }
        return totales.monto().divide(BigDecimal.valueOf(totales.acciones()), 4, RoundingMode.HALF_UP);
    }

    record Totales(BigDecimal monto, long acciones) {
    }
}
