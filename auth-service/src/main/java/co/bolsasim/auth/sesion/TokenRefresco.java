package co.bolsasim.auth.sesion;

import co.bolsasim.auth.usuario.Usuario;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Tabla TOKEN_REFRESCO del modelo entidad-relación: una sesión abierta (HU-02). Con el token de
 * refresco el usuario pide nuevos tokens de acceso sin escribir otra vez su contraseña.
 *
 * <p>Solo se guarda el hash del token, nunca el token: así una copia robada de la base no sirve
 * para entrar a la cuenta de nadie.
 */
@Entity
@Table(name = "token_refresco")
public class TokenRefresco {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "usuario_id")
    private Usuario usuario;

    @Column(name = "token_hash")
    private String tokenHash;

    @Column(name = "expira_en")
    private Instant expiraEn;

    private boolean revocado;

    protected TokenRefresco() {
        // Lo usa JPA al leer de la base
    }

    public TokenRefresco(Usuario usuario, String tokenHash, Instant expiraEn) {
        this.usuario = usuario;
        this.tokenHash = tokenHash;
        this.expiraEn = expiraEn;
    }

    /** Sirve para renovar el token de acceso: no se cerró la sesión y no han pasado los 7 días. */
    public boolean vigente(Instant ahora) {
        return !revocado && ahora.isBefore(expiraEn);
    }

    /** Al cerrar sesión (RF-03). No se borra: queda el registro de que existió. */
    public void revocar() {
        this.revocado = true;
    }

    public UUID getId() {
        return id;
    }

    public Usuario getUsuario() {
        return usuario;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiraEn() {
        return expiraEn;
    }

    public boolean isRevocado() {
        return revocado;
    }
}
