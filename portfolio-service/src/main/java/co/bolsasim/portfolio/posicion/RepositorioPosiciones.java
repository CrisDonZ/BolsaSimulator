package co.bolsasim.portfolio.posicion;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Tabla POSICION: las acciones de cada cuenta, con JDBC. La consulta del portafolio llega con HU-11.
 *
 * <p>Como en CUENTA, cada cambio es un solo UPDATE que suma o resta sobre el valor de la base, con su condición en
 * el mismo WHERE. La reserva de una venta (partición por usuario) y la liquidación (partición por símbolo) pueden
 * tocar la misma fila a la vez: PostgreSQL bloquea la fila, la segunda espera a que la primera confirme y vuelve a
 * revisar su condición con el valor nuevo. Así nunca se reservan ni se entregan acciones que ya no están.
 */
@Repository
public class RepositorioPosiciones {

    private final JdbcClient jdbc;

    public RepositorioPosiciones(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Suma acciones compradas o emitidas y recalcula el costo promedio:
     * (costo × cantidad + precio × nuevas) / (cantidad + nuevas), con 4 decimales. La primera vez que la cuenta
     * recibe un símbolo crea la posición; las siguientes la actualizan en el mismo INSERT, sin leerla antes.
     */
    public void acreditar(UUID cuentaId, String simbolo, long cantidad, long precio) {
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

    /**
     * Bloquea acciones para una venta, solo si hay suficientes libres (RF-17).
     *
     * @return false si no alcanzan o la cuenta no tiene ese símbolo; la posición no cambia
     */
    public boolean reservar(UUID cuentaId, String simbolo, long cantidad) {
        return jdbc.sql("""
                        UPDATE posicion
                        SET cantidad_reservada = cantidad_reservada + :cantidad, version = version + 1
                        WHERE cuenta_id = :cuenta AND simbolo = :simbolo AND cantidad - cantidad_reservada >= :cantidad""")
                .param("cuenta", cuentaId)
                .param("simbolo", simbolo)
                .param("cantidad", cantidad)
                .update() == 1;
    }

    /**
     * Entrega acciones vendidas: salen de la posición y de lo reservado. El costo promedio no cambia: las que
     * quedan costaron lo mismo.
     *
     * @return false si no había tantas reservadas; la posición no cambia
     */
    public boolean entregar(UUID cuentaId, String simbolo, long cantidad) {
        return jdbc.sql("""
                        UPDATE posicion
                        SET cantidad = cantidad - :cantidad, cantidad_reservada = cantidad_reservada - :cantidad,
                            version = version + 1
                        WHERE cuenta_id = :cuenta AND simbolo = :simbolo AND cantidad_reservada >= :cantidad""")
                .param("cuenta", cuentaId)
                .param("simbolo", simbolo)
                .param("cantidad", cantidad)
                .update() == 1;
    }

    /**
     * Devuelve acciones reservadas que no se vendieron: vuelven a estar libres.
     *
     * @return false si no había tantas reservadas; la posición no cambia
     */
    public boolean liberar(UUID cuentaId, String simbolo, long cantidad) {
        return jdbc.sql("""
                        UPDATE posicion
                        SET cantidad_reservada = cantidad_reservada - :cantidad, version = version + 1
                        WHERE cuenta_id = :cuenta AND simbolo = :simbolo AND cantidad_reservada >= :cantidad""")
                .param("cuenta", cuentaId)
                .param("simbolo", simbolo)
                .param("cantidad", cantidad)
                .update() == 1;
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
