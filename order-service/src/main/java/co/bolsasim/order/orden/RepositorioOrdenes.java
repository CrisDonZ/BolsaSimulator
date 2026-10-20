package co.bolsasim.order.orden;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data genera la implementación. La lista paginada está en {@link ConsultaOrdenes}. */
public interface RepositorioOrdenes extends JpaRepository<Orden, UUID> {

    /**
     * Busca por id y dueño en la misma consulta: la orden de otro usuario y una que no existe dan el mismo
     * resultado, así la respuesta no revela qué órdenes existen (RNF-23).
     */
    Optional<Orden> findByIdAndUsuarioId(UUID id, UUID usuarioId);
}
