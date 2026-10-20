package co.bolsasim.auth.web;

import co.bolsasim.auth.servicio.ServicioUsuarios;
import co.bolsasim.auth.servicio.UsuarioNoEncontradoException;
import co.bolsasim.auth.usuario.Rol;
import co.bolsasim.auth.usuario.Usuario;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/usuarios")
public class UsuarioController {

    private final ServicioUsuarios servicioUsuarios;

    public UsuarioController(ServicioUsuarios servicioUsuarios) {
        this.servicioUsuarios = servicioUsuarios;
    }

    /**
     * Responde 201 Created (no 202 como las órdenes): el usuario ya quedó guardado cuando
     * llega la respuesta. El header Location dice dónde consultarlo.
     */
    @PostMapping
    public ResponseEntity<UsuarioResponse> registrar(@Valid @RequestBody RegistroUsuarioRequest request) {
        Usuario usuario = servicioUsuarios.registrar(request);
        return ResponseEntity.created(URI.create("/api/usuarios/" + usuario.getId()))
                .body(UsuarioResponse.de(usuario));
    }

    /**
     * Cada usuario solo puede consultar su propio perfil; un ADMIN puede consultar cualquiera (RNF-23).
     *
     * <p>Pedir el perfil de otro responde 404, igual que un id que no existe, y sin consultar la base:
     * la respuesta no revela si ese usuario existe.
     */
    @GetMapping("/{id}")
    public UsuarioResponse buscar(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
        boolean esSuPerfil = id.toString().equals(token.getSubject());
        boolean esAdmin = Rol.ADMIN.name().equals(token.getClaimAsString("rol"));
        if (!esSuPerfil && !esAdmin) {
            throw new UsuarioNoEncontradoException(id);
        }
        return UsuarioResponse.de(servicioUsuarios.buscar(id));
    }
}
