package co.bolsasim.auth.usuario;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;

/**
 * Tabla USUARIO del modelo entidad-relación. Su {@code id} es el que los demás servicios
 * usan como {@code usuarioId}: por ejemplo, el dueño de una orden en order-service.
 *
 * <p>Es una clase y no un record porque JPA necesita un constructor vacío y poder
 * cambiar campos, como el estado al bloquear al usuario.
 */
@Entity
public class Usuario {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String nombre;

    private String alias;

    private String correo;

    // Nunca la contraseña: solo su hash BCrypt (RNF-21)
    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    private Rol rol;

    @Enumerated(EnumType.STRING)
    private EstadoUsuario estado;

    @Column(name = "fecha_autorizacion_datos")
    private Instant fechaAutorizacionDatos;

    @Column(name = "creado_en")
    private Instant creadoEn;

    protected Usuario() {
        // Lo usa JPA al leer de la base
    }

    private Usuario(String nombre, String alias, String correo, String passwordHash, Rol rol, Instant ahora) {
        this.nombre = nombre;
        this.alias = alias;
        this.correo = correo;
        this.passwordHash = passwordHash;
        this.rol = rol;
        this.estado = EstadoUsuario.ACTIVO;
        this.fechaAutorizacionDatos = ahora;
        this.creadoEn = ahora;
    }

    /** Un estudiante que se registra: aceptó la política de datos en este momento (Ley 1581). */
    public static Usuario nuevoInversionista(String nombre, String alias, String correo, String passwordHash,
                                             Instant ahora) {
        return new Usuario(nombre, alias, correo, passwordHash, Rol.INVERSIONISTA, ahora);
    }

    public UUID getId() {
        return id;
    }

    public String getNombre() {
        return nombre;
    }

    public String getAlias() {
        return alias;
    }

    public String getCorreo() {
        return correo;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Rol getRol() {
        return rol;
    }

    public EstadoUsuario getEstado() {
        return estado;
    }

    public Instant getFechaAutorizacionDatos() {
        return fechaAutorizacionDatos;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }
}
