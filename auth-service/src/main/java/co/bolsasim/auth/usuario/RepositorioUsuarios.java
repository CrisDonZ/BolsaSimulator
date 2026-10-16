package co.bolsasim.auth.usuario;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data genera la implementación a partir de los nombres de los métodos. */
public interface RepositorioUsuarios extends JpaRepository<Usuario, UUID> {

    Optional<Usuario> findByCorreo(String correo);

    boolean existsByCorreo(String correo);

    boolean existsByAliasIgnoreCase(String alias);
}
