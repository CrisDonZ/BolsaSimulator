package co.bolsasim.auth.servicio;

import co.bolsasim.auth.usuario.RepositorioUsuarios;
import co.bolsasim.auth.usuario.Usuario;
import co.bolsasim.auth.web.RegistroUsuarioRequest;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reglas del registro (HU-01): correo y alias únicos, y la contraseña se guarda cifrada.
 *
 * <p>Siguiente paso: publicar {@code users.registered} para que portfolio-service cree
 * la cuenta con el saldo inicial (RF-07), y que order-service rechace órdenes de usuarios
 * que no existen.
 */
@Service
public class ServicioUsuarios {

    private final RepositorioUsuarios repositorio;
    private final PasswordEncoder cifrador;

    public ServicioUsuarios(RepositorioUsuarios repositorio, PasswordEncoder cifrador) {
        this.repositorio = repositorio;
        this.cifrador = cifrador;
    }

    @Transactional
    public Usuario registrar(RegistroUsuarioRequest request) {
        // Ana@Correo.com y ana@correo.com son el mismo correo
        String correo = request.correo().trim().toLowerCase(Locale.ROOT);
        String alias = request.alias().trim();

        if (repositorio.existsByCorreo(correo)) {
            throw new UsuarioDuplicadoException("Ese correo ya tiene una cuenta.");
        }
        if (repositorio.existsByAliasIgnoreCase(alias)) {
            throw new UsuarioDuplicadoException("El alias " + alias + " ya está en uso: elige otro.");
        }

        Usuario usuario = Usuario.nuevoInversionista(
                request.nombre().trim(), alias, correo, cifrador.encode(request.contrasena()), Instant.now());

        // saveAndFlush: si dos registros iguales llegan a la vez, la restricción UNIQUE de la base
        // falla aquí mismo y el ManejadorErrores responde 409
        return repositorio.saveAndFlush(usuario);
    }

    @Transactional(readOnly = true)
    public Usuario buscar(UUID id) {
        return repositorio.findById(id).orElseThrow(() -> new UsuarioNoEncontradoException(id));
    }
}
