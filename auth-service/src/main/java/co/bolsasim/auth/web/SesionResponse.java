package co.bolsasim.auth.web;

import co.bolsasim.auth.servicio.Sesion;
import java.time.Instant;

/**
 * Respuesta del login. El token de acceso va en cada petición en el header
 * {@code Authorization: Bearer <tokenAcceso>}; el de refresco solo se usa en /refresh y /logout.
 */
public record SesionResponse(
        String tokenAcceso,
        String tipoToken,
        Instant accesoExpiraEn,
        String tokenRefresco,
        Instant refrescoExpiraEn) {

    public static SesionResponse de(Sesion sesion) {
        return new SesionResponse(sesion.acceso().valor(), "Bearer", sesion.acceso().expiraEn(),
                sesion.tokenRefresco(), sesion.refrescoExpiraEn());
    }
}
