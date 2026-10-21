package co.bolsasim.portfolio.posicion;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Tabla POSICION: las acciones de cada cuenta, con JDBC. La consulta del portafolio llega con HU-11. */
@Repository
public class RepositorioPosiciones {

    private final JdbcClient jdbc;

    public RepositorioPosiciones(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Suma las acciones de una compra y recalcula el costo promedio:
     * (costo × cantidad + precio × compradas) / (cantidad + compradas), con 4 decimales. La primera compra de un
     * símbolo crea la posición; las siguientes la actualizan en el mismo INSERT, sin leerla antes.
     */
    public void acreditarCompra(UUID cuentaId, String simbolo, int cantidad, long precio) {
        jdbc.sql("""
                        INSERT INTO posicion (id, cuenta_id, simbolo, cantidad, cantidad_reservada, costo_promedio, version)
                        VALUES (:id, :cuenta, :simbolo, :cantidad, 0, :precio, 0)
                        ON CONFLICT (cuenta_id, simbolo) DO UPDATE
                        SET costo_promedio = ROUND((posicion.costo_promedio * posicion.cantidad + :precio * :cantidad)
                                                   / (posicion.cantidad + :cantidad), 4),
                            cantidad = posicion.cantidad + :cantidad,
                            version = posicion.version + 1""")
                .param("id", UUID.randomUUID())
                .param("cuenta", cuentaId)
                .param("simbolo", simbolo)
                .param("cantidad", cantidad)
                .param("precio", precio)
                .update();
    }

    public Optional<Posicion> buscar(UUID cuentaId, String simbolo) {
        return jdbc.sql("""
                        SELECT simbolo, cantidad, cantidad_reservada, costo_promedio
                        FROM posicion WHERE cuenta_id = :cuenta AND simbolo = :simbolo""")
                .param("cuenta", cuentaId)
                .param("simbolo", simbolo)
                .query(Posicion.class)
                .optional();
    }
}
