package co.bolsasim.auth.web;

import co.bolsasim.auth.usuario.EstadoUsuario;
import co.bolsasim.auth.usuario.Rol;
import co.bolsasim.auth.usuario.Usuario;
import java.time.Instant;
import java.util.UUID;

/**
 * Lo que la API muestra de un usuario. El hash de la contraseña nunca sale del servicio.
 * El {@code id} es el que se usa como {@code usuarioId} al enviar órdenes.
 */
public record UsuarioResponse(
        UUID id,
        String nombre,
        String alias,
        String correo,
        Rol rol,
        EstadoUsuario estado,
        Instant creadoEn) {

    public static UsuarioResponse de(Usuario usuario) {
        return new UsuarioResponse(usuario.getId(), usuario.getNombre(), usuario.getAlias(), usuario.getCorreo(),
                usuario.getRol(), usuario.getEstado(), usuario.getCreadoEn());
    }
}
