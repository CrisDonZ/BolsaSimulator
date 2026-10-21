package co.bolsasim.portfolio.cuenta;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Tabla CUENTA, con JDBC. Cada cambio de saldo es un solo UPDATE que suma o resta sobre el valor de la base, no
 * sobre uno leído antes: PostgreSQL bloquea la fila mientras tanto, así dos eventos a la vez sobre la misma cuenta
 * no se pisan (regla 5) y la condición de saldo suficiente se revisa en el mismo paso que el cambio.
 */
@Repository
public class RepositorioCuentas {

    private final JdbcClient jdbc;

    public RepositorioCuentas(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Crea la cuenta del usuario si todavía no tiene. Si dos llegan a la vez (el evento users.registered y la
     * primera consulta), PostgreSQL hace esperar a la segunda en el índice único de usuario_id y, cuando la
     * primera confirma, la segunda no inserta nada: nunca hay dos cuentas, y nadie recibe un error.
     *
     * @return el id de la cuenta nueva, o vacío si el usuario ya tenía una
     */
    public Optional<UUID> crearSiNoExiste(UUID usuarioId, long saldoInicial, Instant ahora) {
        return jdbc.sql("""
                        INSERT INTO cuenta (id, usuario_id, saldo_inicial, saldo_disponible, saldo_reservado, version,
                                            actualizado_en)
                        VALUES (:id, :usuario, :saldo, :saldo, 0, 0, :ahora)
                        ON CONFLICT (usuario_id) DO NOTHING
                        RETURNING id""")
                .param("id", UUID.randomUUID())
                .param("usuario", usuarioId)
                .param("saldo", saldoInicial)
                .param("ahora", ahora.atOffset(ZoneOffset.UTC))
                .query(UUID.class)
                .optional();
    }

    public Optional<Cuenta> buscarPorUsuario(UUID usuarioId) {
        return jdbc.sql("""
                        SELECT id, usuario_id, saldo_inicial, saldo_disponible, saldo_reservado, actualizado_en
                        FROM cuenta WHERE usuario_id = :usuario""")
                .param("usuario", usuarioId)
                .query(Cuenta.class)
                .optional();
    }

    /**
     * Pasa ese monto del disponible al reservado, solo si el disponible alcanza.
     *
     * @return false si no alcanza; la cuenta no cambia
     */
    public boolean reservar(UUID cuentaId, long monto, Instant ahora) {
        return jdbc.sql("""
                        UPDATE cuenta
                        SET saldo_disponible = saldo_disponible - :monto, saldo_reservado = saldo_reservado + :monto,
                            version = version + 1, actualizado_en = :ahora
                        WHERE id = :id AND saldo_disponible >= :monto""")
                .param("id", cuentaId)
                .param("monto", monto)
                .param("ahora", ahora.atOffset(ZoneOffset.UTC))
                .update() == 1;
    }

    /**
     * Paga una compra: sale del reservado lo que cubre la reserva y del disponible lo que no alcanzó a cubrir (solo
     * en una compra a mercado que se ejecuta por encima de lo reservado), y devuelve al disponible lo que se
     * libera.
     *
     * @return false si el disponible no alcanza para la parte que no cubría la reserva; la cuenta no cambia
     */
    public boolean pagarYLiberar(UUID cuentaId, long delReservado, long delDisponible, long liberado, Instant ahora) {
        return jdbc.sql("""
                        UPDATE cuenta
                        SET saldo_reservado = saldo_reservado - :delReservado - :liberado,
                            saldo_disponible = saldo_disponible - :delDisponible + :liberado,
                            version = version + 1, actualizado_en = :ahora
                        WHERE id = :id AND saldo_disponible + :liberado >= :delDisponible""")
                .param("id", cuentaId)
                .param("delReservado", delReservado)
                .param("delDisponible", delDisponible)
                .param("liberado", liberado)
                .param("ahora", ahora.atOffset(ZoneOffset.UTC))
                .update() == 1;
    }
}
