package co.bolsasim.auth.web;

import co.bolsasim.auth.servicio.ServicioUsuarios;
import co.bolsasim.auth.usuario.Usuario;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
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

    // Cuando el gateway valide el JWT, cada usuario solo podrá consultarse a sí mismo (RNF-23)
    @GetMapping("/{id}")
    public UsuarioResponse buscar(@PathVariable UUID id) {
        return UsuarioResponse.de(servicioUsuarios.buscar(id));
    }
}
