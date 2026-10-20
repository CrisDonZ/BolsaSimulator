package co.bolsasim.order.orden;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * La lista de órdenes de un usuario, paginada por cursor (keyset) y no con OFFSET.
 *
 * <p>Con OFFSET, la página 500 obliga a PostgreSQL a leer y descartar 10.000 filas, y contar el total recorre
 * todas las órdenes del usuario en cada petición; con un bot que envía una orden cada 200 ms eso pesa. Aquí
 * cada página pide "las siguientes a esta orden": PostgreSQL salta en el índice justo a ese punto y lee solo
 * las filas de la página, ya ordenadas. Cuesta lo mismo la primera página que la número 10.000, y una orden
 * nueva no corre las páginas siguientes.
 *
 * <p>Cada filtro tiene su índice (ver V3__crear_orden.sql). Por eso la consulta se arma con fragmentos fijos
 * en vez de un "(:estado IS NULL OR estado = :estado)": con esa forma PostgreSQL no puede elegir el índice
 * del filtro. Ningún fragmento lleva texto que venga de la petición; los valores van como parámetros.
 */
@Repository
public class ConsultaOrdenes {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * @param despues el cursor de la página anterior; null para la primera página
     * @param limite  cuántas filas leer como máximo
     */
    @SuppressWarnings("unchecked")
    public List<Orden> buscar(UUID usuarioId, FiltroEstado filtro, Cursor despues, int limite) {
        Query consulta = entityManager.createNativeQuery(sql(filtro, despues != null), Orden.class)
                .setParameter("usuario", usuarioId)
                .setParameter("limite", limite);
        if (filtro.estado() != null) {
            consulta.setParameter("estado", filtro.estado().name());
        }
        if (despues != null) {
            consulta.setParameter("creadaEn", despues.creadaEn())
                    .setParameter("id", despues.id());
        }
        return consulta.getResultList();
    }

    /** La consulta para ese filtro. Visible para la prueba que revisa con EXPLAIN que usa su índice. */
    static String sql(FiltroEstado filtro, boolean conCursor) {
        StringBuilder sql = new StringBuilder("SELECT * FROM orden WHERE usuario_id = :usuario");
        if (filtro.soloAbiertas()) {
            // Igual a la condición del índice parcial orden_usuario_abiertas: así PostgreSQL sabe que puede usarlo
            sql.append(" AND estado IN ('ABIERTA', 'PARCIAL')");
        } else if (filtro.estado() != null) {
            sql.append(" AND estado = :estado");
        }
        if (conCursor) {
            // Comparación de filas: "después de (fecha, id)" en el orden de la lista, en una sola condición que
            // el índice resuelve directamente
            sql.append(" AND (creada_en, id) < (:creadaEn, :id)");
        }
        return sql.append(" ORDER BY creada_en DESC, id DESC LIMIT :limite").toString();
    }
}
