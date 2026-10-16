package co.bolsasim.auth.web;

import co.bolsasim.auth.servicio.TokenAcceso;
import java.time.Instant;

/** Respuesta de /refresh: un token de acceso nuevo. El token de refresco sigue siendo el mismo. */
public record TokenAccesoResponse(String tokenAcceso, String tipoToken, Instant accesoExpiraEn) {

    public static TokenAccesoResponse de(TokenAcceso token) {
        return new TokenAccesoResponse(token.valor(), "Bearer", token.expiraEn());
    }
}
