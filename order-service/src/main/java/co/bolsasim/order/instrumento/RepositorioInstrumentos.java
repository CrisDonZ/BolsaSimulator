package co.bolsasim.order.instrumento;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data genera la implementación. */
public interface RepositorioInstrumentos extends JpaRepository<Instrumento, String> {

    /**
     * Guarda el precio de una operación como último precio, salvo que la copia ya tenga uno de una operación
     * posterior. En un solo UPDATE: dos operaciones a la vez no se pisan, y la misma operación dos veces deja lo
     * mismo.
     *
     * @return 1 si cambió, 0 si el instrumento no está en la copia o ya tenía un precio más nuevo
     */
    @Modifying
    @Query("""
            UPDATE Instrumento i SET i.ultimoPrecio = :precio, i.ultimoPrecioEn = :en
            WHERE i.simbolo = :simbolo AND (i.ultimoPrecioEn IS NULL OR i.ultimoPrecioEn <= :en)""")
    int actualizarUltimoPrecio(@Param("simbolo") String simbolo, @Param("precio") long precio,
                               @Param("en") Instant en);
}
