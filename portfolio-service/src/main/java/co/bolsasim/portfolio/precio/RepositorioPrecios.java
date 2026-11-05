package co.bolsasim.portfolio.precio;

import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Tabla ULTIMO_PRECIO: el precio de la última operación de cada símbolo, con JDBC. */
@Repository
public class RepositorioPrecios {

    private final JdbcClient jdbc;

    public RepositorioPrecios(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Guarda el precio de una operación como último precio, salvo que el símbolo ya tenga uno de una operación
     * posterior. En un solo INSERT: dos operaciones a la vez no se pisan, y la misma operación dos veces deja lo
     * mismo.
     *
     * @return true si cambió, false si ya tenía un precio más nuevo
     */
    public boolean actualizar(String simbolo, long precio, Instant actualizadoEn) {
        return jdbc.sql("""
                        INSERT INTO ultimo_precio (simbolo, precio, actualizado_en)
                        VALUES (:simbolo, :precio, :en)
                        ON CONFLICT (simbolo) DO UPDATE
                        SET precio = EXCLUDED.precio, actualizado_en = EXCLUDED.actualizado_en
                        WHERE ultimo_precio.actualizado_en <= EXCLUDED.actualizado_en""")
                .param("simbolo", simbolo)
                .param("precio", precio)
                .param("en", actualizadoEn.atOffset(ZoneOffset.UTC))
                .update() == 1;
    }
}
