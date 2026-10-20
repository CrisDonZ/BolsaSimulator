package co.bolsasim.auth.web;

import co.bolsasim.auth.servicio.ServicioSesiones;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Iniciar sesión, renovar el token de acceso y cerrar sesión (HU-02). */
@RestController
@RequestMapping("/api/auth")
public class SesionController {

    private final ServicioSesiones servicioSesiones;

    public SesionController(ServicioSesiones servicioSesiones) {
        this.servicioSesiones = servicioSesiones;
    }

    @PostMapping("/login")
    public SesionResponse iniciar(@Valid @RequestBody LoginRequest request) {
        return SesionResponse.de(servicioSesiones.iniciar(request.correo(), request.contrasena()));
    }

    @PostMapping("/refresh")
    public TokenAccesoResponse renovar(@Valid @RequestBody TokenRefrescoRequest request) {
        return TokenAccesoResponse.de(servicioSesiones.renovar(request.tokenRefresco()));
    }

    /**
     * 204 siempre, aunque el token no exista o sea de otro usuario: así la respuesta no dice qué tokens
     * son válidos. Solo se revoca si el token de refresco es del usuario del token de acceso.
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cerrar(@AuthenticationPrincipal Jwt token, @Valid @RequestBody TokenRefrescoRequest request) {
        servicioSesiones.cerrar(token.getSubject(), request.tokenRefresco());
    }
}
