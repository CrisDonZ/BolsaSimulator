package co.bolsasim.portfolio.web;

import co.bolsasim.portfolio.servicio.ServicioCuentas;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * La cuenta del usuario del token ("sub"). Las rutas no llevan id de usuario: no hay forma de pedir la cuenta de
 * otro (RNF-23).
 */
@RestController
@RequestMapping("/api/cuenta")
public class CuentaController {

    private final ServicioCuentas servicio;

    public CuentaController(ServicioCuentas servicio) {
        this.servicio = servicio;
    }

    /** Saldo disponible, reservado y total (RF-08). Si la cuenta todavía no existe, se abre ahora. */
    @GetMapping
    public CuentaResponse saldo(@AuthenticationPrincipal Jwt token) {
        return CuentaResponse.de(servicio.consultar(usuario(token), token.getClaimAsString("rol")));
    }

    /**
     * Los movimientos de dinero, del más reciente al más viejo (RF-09, RF-10). Para la página siguiente, envía en
     * despues el valor "siguiente" de esta respuesta, con los mismos filtros.
     *
     * @param tipo  SALDO_INICIAL, RESERVA, LIBERACION, COMPRA, VENTA o REINICIO; sin él, todos
     * @param desde día inicial AAAA-MM-DD (hora de Colombia), incluido
     * @param hasta día final AAAA-MM-DD, incluido completo
     */
    @GetMapping("/movimientos")
    public PaginaMovimientosResponse movimientos(@AuthenticationPrincipal Jwt token,
                                                 @RequestParam(required = false) String tipo,
                                                 @RequestParam(required = false) String desde,
                                                 @RequestParam(required = false) String hasta,
                                                 @RequestParam(defaultValue = "" + ServicioCuentas.TAMANO_POR_DEFECTO) int tamano,
                                                 @RequestParam(required = false) String despues) {
        return PaginaMovimientosResponse.de(servicio.movimientos(usuario(token), token.getClaimAsString("rol"),
                tipo, desde, hasta, tamano, despues));
    }

    /** El "sub" de los tokens de auth-service siempre es un UUID; si no, el token no es de un usuario. */
    private static UUID usuario(Jwt token) {
        return UUID.fromString(token.getSubject());
    }
}
