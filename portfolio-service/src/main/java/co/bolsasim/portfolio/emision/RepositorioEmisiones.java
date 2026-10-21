package co.bolsasim.portfolio.emision;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Tabla EMISION, con JDBC. El símbolo es la llave: cada uno se emite una sola vez. */
@Repository
public class RepositorioEmisiones {

    /**
     * Número fijo del advisory lock que ordena las emisiones y el alta del creador de mercado; cualquier otro número
     * sería igual de válido, salvo el del publicador del outbox.
     */
    static final long BLOQUEO_EMISIONES = 2_026_102_105L;

    private final JdbcClient jdbc;

    public RepositorioEmisiones(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Espera su turno hasta que termine la transacción (_xact_): la emisión de un instrumento y el alta del creador
     * de mercado nunca corren a la vez. Sin esto, la primera podría no ver al creador y dejar la emisión PENDIENTE
     * mientras el segundo, que todavía no ve esa emisión, busca las pendientes: quedaría pendiente para siempre.
     */
    public void esperarTurno() {
        // pg_advisory_xact_lock devuelve void: se pide un 1 para no leer un valor vacío
        jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(:bloqueo)")
                .param("bloqueo", BLOQUEO_EMISIONES)
                .query(Integer.class)
                .single();
    }

    public boolean existe(String simbolo) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM emision WHERE simbolo = :simbolo)")
                .param("simbolo", simbolo)
                .query(Boolean.class)
                .single();
    }

    /** La emisión ya acreditada a la cuenta del creador de mercado. */
    public void guardarAplicada(Emision emision, UUID cuentaId, Instant ahora) {
        jdbc.sql("""
                        INSERT INTO emision (simbolo, acciones_emitidas, precio_inicial, estado, cuenta_id, creada_en,
                                             aplicada_en)
                        VALUES (:simbolo, :acciones, :precio, 'APLICADA', :cuenta, :ahora, :ahora)""")
                .param("simbolo", emision.simbolo())
                .param("acciones", emision.accionesEmitidas())
                .param("precio", emision.precioInicial())
                .param("cuenta", cuentaId)
                .param("ahora", ahora.atOffset(ZoneOffset.UTC))
                .update();
    }

    /** La emisión que espera a que exista la cuenta del creador de mercado. */
    public void guardarPendiente(Emision emision, Instant ahora) {
        jdbc.sql("""
                        INSERT INTO emision (simbolo, acciones_emitidas, precio_inicial, estado, creada_en)
                        VALUES (:simbolo, :acciones, :precio, 'PENDIENTE', :ahora)""")
                .param("simbolo", emision.simbolo())
                .param("acciones", emision.accionesEmitidas())
                .param("precio", emision.precioInicial())
                .param("ahora", ahora.atOffset(ZoneOffset.UTC))
                .update();
    }

    public List<Emision> pendientes() {
        return jdbc.sql("""
                        SELECT simbolo, acciones_emitidas, precio_inicial FROM emision
                        WHERE estado = 'PENDIENTE' ORDER BY creada_en, simbolo""")
                .query(Emision.class)
                .list();
    }

    public void marcarAplicada(String simbolo, UUID cuentaId, Instant ahora) {
        jdbc.sql("""
                        UPDATE emision SET estado = 'APLICADA', cuenta_id = :cuenta, aplicada_en = :ahora
                        WHERE simbolo = :simbolo AND estado = 'PENDIENTE'""")
                .param("simbolo", simbolo)
                .param("cuenta", cuentaId)
                .param("ahora", ahora.atOffset(ZoneOffset.UTC))
                .update();
    }
}
