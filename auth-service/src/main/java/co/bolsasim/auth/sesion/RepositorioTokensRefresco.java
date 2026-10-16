package co.bolsasim.auth.sesion;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data genera la implementación a partir de los nombres de los métodos. */
public interface RepositorioTokensRefresco extends JpaRepository<TokenRefresco, UUID> {

    Optional<TokenRefresco> findByTokenHash(String tokenHash);
}
