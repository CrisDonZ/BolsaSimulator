package co.bolsasim.portfolio.seguridad;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Respuesta 401 en formato ProblemDetail (RFC 9457) con el header {@code WWW-Authenticate: Bearer}
 * (RFC 6750). Usa los mismos textos que el gateway: el cliente ve lo mismo entre por donde entre.
 *
 * <p>No dice si el token venció o si la firma no coincide: eso solo ayudaría a quien intenta
 * falsificarlo. El motivo exacto queda en el log.
 */
@Component
public class RespuestaNoAutorizado implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(RespuestaNoAutorizado.class);

    private static final String SIN_TOKEN =
            "Falta el token de acceso. Inicia sesión y envíalo en el header Authorization: Bearer <token>.";
    private static final String TOKEN_INVALIDO =
            "El token de acceso no es válido o ya venció. Renuévalo con /api/auth/refresh o inicia sesión de nuevo.";

    @Override
    public void commence(HttpServletRequest peticion, HttpServletResponse respuesta, AuthenticationException error)
            throws IOException {
        boolean tokenInvalido = error instanceof InvalidBearerTokenException;
        log.debug("401 en {} {}: {}", peticion.getMethod(), peticion.getRequestURI(), error.getMessage());

        respuesta.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        respuesta.setHeader(HttpHeaders.WWW_AUTHENTICATE, tokenInvalido ? "Bearer error=\"invalid_token\"" : "Bearer");
        respuesta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        respuesta.setCharacterEncoding(StandardCharsets.UTF_8.name());

        // Textos fijos, sin nada que venga de la petición: se puede armar el JSON a mano sin riesgo
        respuesta.getWriter().write("""
                {"type":"about:blank","title":"No autorizado","status":401,"detail":"%s"}"""
                .formatted(tokenInvalido ? TOKEN_INVALIDO : SIN_TOKEN));
    }
}
