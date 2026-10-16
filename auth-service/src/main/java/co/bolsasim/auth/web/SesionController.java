package co.bolsasim.auth.web;

import co.bolsasim.auth.servicio.ServicioSesiones;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

    /** 204 siempre, aunque el token no exista: así la respuesta no dice qué tokens son válidos. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cerrar(@Valid @RequestBody TokenRefrescoRequest request) {
        servicioSesiones.cerrar(request.tokenRefresco());
    }
}
