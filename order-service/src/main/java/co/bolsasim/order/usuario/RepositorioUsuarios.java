package co.bolsasim.order.usuario;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data genera la implementación. existsById responde si el usuario está registrado. */
public interface RepositorioUsuarios extends JpaRepository<Usuario, UUID> {
}
