package co.bolsasim.portfolio.reserva;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Tabla RESERVA, con JDBC. */
@Repository
public class RepositorioReservas {

    private final JdbcClient jdbc;

    public RepositorioReservas(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Una reserva de dinero ACTIVA, sin nada pagado ni devuelto todavía. */
    public void crearDeDinero(UUID cuentaId, UUID ordenId, Long precioLimite, long monto, Instant ahora) {
        jdbc.sql("""
                        INSERT INTO reserva (id, cuenta_id, orden_id, tipo, simbolo, precio_limite, monto_reservado,
                                             monto_consumido, monto_liberado, estado, creada_en, actualizada_en)
                        VALUES (:id, :cuenta, :orden, 'DINERO', NULL, :precioLimite, :monto, 0, 0, 'ACTIVA', :ahora,
                                :ahora)""")
                .param("id", UUID.randomUUID())
                .param("cuenta", cuentaId)
                .param("orden", ordenId)
                .param("precioLimite", precioLimite)
                .param("monto", monto)
                .param("ahora", ahora.atOffset(ZoneOffset.UTC))
                .update();
    }

    public boolean existeParaLaOrden(UUID ordenId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM reserva WHERE orden_id = :orden)")
                .param("orden", ordenId)
                .query(Boolean.class)
                .single();
    }

    /**
     * La reserva de la orden, bloqueada hasta que termine la transacción (FOR UPDATE): dos eventos de la misma
     * orden no la cambian a la vez.
     */
    public Optional<Reserva> bloquearPorOrden(UUID ordenId) {
        return jdbc.sql("""
                        SELECT r.id, r.cuenta_id, c.usuario_id, r.orden_id, r.precio_limite, r.monto_reservado,
                               r.monto_consumido, r.monto_liberado, r.estado
                        FROM reserva r JOIN cuenta c ON c.id = r.cuenta_id
                        WHERE r.orden_id = :orden
                        FOR UPDATE OF r""")
                .param("orden", ordenId)
                .query(Reserva.class)
                .optional();
    }

    /**
     * Suma lo pagado y lo devuelto, y la deja en ese estado. Los CHECK de la tabla impiden pasarse de lo reservado
     * y terminarla con algo pendiente.
     */
    public void registrar(UUID reservaId, long consumido, long liberado, EstadoReserva estado, Instant ahora) {
        jdbc.sql("""
                        UPDATE reserva
                        SET monto_consumido = monto_consumido + :consumido, monto_liberado = monto_liberado + :liberado,
                            estado = :estado, actualizada_en = :ahora
                        WHERE id = :id""")
                .param("id", reservaId)
                .param("consumido", consumido)
                .param("liberado", liberado)
                .param("estado", estado.name())
                .param("ahora", ahora.atOffset(ZoneOffset.UTC))
                .update();
    }
}
