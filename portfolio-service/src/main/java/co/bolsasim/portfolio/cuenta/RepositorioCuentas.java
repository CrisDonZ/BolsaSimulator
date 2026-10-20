package co.bolsasim.portfolio.cuenta;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Tabla CUENTA, con JDBC. */
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
}
