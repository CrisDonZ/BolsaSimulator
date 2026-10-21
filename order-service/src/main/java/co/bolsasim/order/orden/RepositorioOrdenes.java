package co.bolsasim.order.orden;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data genera la implementación. La lista paginada está en {@link ConsultaOrdenes}. */
public interface RepositorioOrdenes extends JpaRepository<Orden, UUID> {

    /**
     * Busca por id y dueño en la misma consulta: la orden de otro usuario y una que no existe dan el mismo
     * resultado, así la respuesta no revela qué órdenes existen (RNF-23).
     */
    Optional<Orden> findByIdAndUsuarioId(UUID id, UUID usuarioId);

    /** La orden que ese usuario ya creó con esa clave de idempotencia, si existe (HU-10). */
    Optional<Orden> findByUsuarioIdAndClaveIdempotencia(UUID usuarioId, String claveIdempotencia);

    /**
     * Marca la cancelación pedida, solo si la orden es de ese usuario, está en el libro (ABIERTA o PARCIAL) y nadie
     * la pidió antes. En un solo UPDATE: si dos solicitudes llegan a la vez, PostgreSQL hace esperar a la segunda,
     * que vuelve a revisar la condición, ya no la cumple y no cambia nada. Así sale un solo CANCELAR.
     *
     * <p>Sube la versión: si un evento del motor había leído la orden antes, su cambio falla por el bloqueo optimista
     * y se vuelve a aplicar con la orden al día. Sin esto, Hibernate escribiría la fila completa y borraría la marca.
     *
     * <p>clearAutomatically: después de un UPDATE directo, la orden que JPA tenga en memoria ya no es la de la base.
     *
     * @return 1 si la marcó, 0 si no cumple la condición
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Orden o SET o.cancelacionSolicitadaEn = :ahora, o.version = o.version + 1
            WHERE o.id = :id AND o.usuarioId = :usuarioId AND o.cancelacionSolicitadaEn IS NULL
              AND o.estado IN (co.bolsasim.eventos.EstadoOrden.ABIERTA, co.bolsasim.eventos.EstadoOrden.PARCIAL)""")
    int solicitarCancelacion(@Param("id") UUID id, @Param("usuarioId") UUID usuarioId, @Param("ahora") Instant ahora);
}
