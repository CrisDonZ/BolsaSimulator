package co.bolsasim.portfolio.web;

import co.bolsasim.portfolio.servicio.ServicioPortafolio;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * El portafolio del usuario del token ("sub"). Como en {@link CuentaController}, la ruta no lleva id de usuario: no
 * hay forma de pedir el portafolio de otro (RNF-23).
 */
@RestController
@RequestMapping("/api/portafolio")
public class PortafolioController {

    private final ServicioPortafolio servicio;

    public PortafolioController(ServicioPortafolio servicio) {
        this.servicio = servicio;
    }

    /** El saldo, cada posición con su valor actual y su ganancia o pérdida, y el valor total (RF-32, RF-33). */
    @GetMapping
    public PortafolioResponse portafolio(@AuthenticationPrincipal Jwt token) {
        return PortafolioResponse.de(servicio.consultar(UUID.fromString(token.getSubject()),
                token.getClaimAsString("rol")));
    }
}
