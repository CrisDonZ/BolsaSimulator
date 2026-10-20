package co.bolsasim.order.instrumento;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data genera la implementación. */
public interface RepositorioInstrumentos extends JpaRepository<Instrumento, String> {
}
