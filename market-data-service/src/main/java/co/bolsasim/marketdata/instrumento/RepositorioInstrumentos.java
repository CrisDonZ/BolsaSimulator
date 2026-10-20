package co.bolsasim.marketdata.instrumento;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Spring Data genera la implementación. */
public interface RepositorioInstrumentos extends JpaRepository<Instrumento, String> {

    List<Instrumento> findAllByOrderBySimboloAsc();

    /**
     * Lee el instrumento y lo bloquea (SELECT ... FOR UPDATE) hasta el final de la transacción. Así dos
     * administradores que cambian el mismo instrumento a la vez no se pisan: si uno lo suspende mientras
     * otro le cambia el nombre, el segundo espera y ve el instrumento ya suspendido. Sin el bloqueo, el
     * evento del segundo diría ACTIVO y los demás servicios volverían a aceptar órdenes.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Instrumento i where i.simbolo = :simbolo")
    Optional<Instrumento> bloquear(String simbolo);
}
