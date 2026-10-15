package co.bolsasim.order.usuario;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Copia local de un usuario que existe en auth-service: solo su id y la fecha de registro.
 * La llena el tópico users.registered; order-service la usa para rechazar órdenes de usuarios desconocidos.
 */
@Entity
@Table(name = "usuario_registrado")
public class Usuario {

    @Id
    @Column(name = "usuario_id")
    private UUID id;

    @Column(name = "registrado_en")
    private Instant registradoEn;

    protected Usuario() {
        // Lo usa JPA al leer de la base
    }

    public Usuario(UUID id, Instant registradoEn) {
        this.id = id;
        this.registradoEn = registradoEn;
    }

    public UUID getId() {
        return id;
    }

    public Instant getRegistradoEn() {
        return registradoEn;
    }
}
