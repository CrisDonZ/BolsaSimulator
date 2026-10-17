package co.bolsasim.gateway;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Respuesta 401 en el mismo formato que los demás servicios (ProblemDetail, RFC 9457), con el header
 * {@code WWW-Authenticate: Bearer} que exige HTTP (RFC 6750).
 *
 * <p>No dice si el token venció o si la firma no coincide: eso solo ayudaría a quien intenta
 * falsificarlo. El motivo exacto queda en el log.
 */
@Component
public class RespuestaNoAutorizado implements ServerAuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(RespuestaNoAutorizado.class);

    private static final String SIN_TOKEN =
            "Falta el token de acceso. Inicia sesión y envíalo en el header Authorization: Bearer <token>.";
    private static final String TOKEN_INVALIDO =
            "El token de acceso no es válido o ya venció. Renuévalo con /api/auth/refresh o inicia sesión de nuevo.";

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException error) {
        boolean tokenInvalido = error instanceof InvalidBearerTokenException;
        log.debug("401 en {} {}: {}", exchange.getRequest().getMethod(), exchange.getRequest().getPath(),
                error.getMessage());

        ServerHttpResponse respuesta = exchange.getResponse();
        respuesta.setStatusCode(HttpStatus.UNAUTHORIZED);
        respuesta.getHeaders().set(HttpHeaders.WWW_AUTHENTICATE,
                tokenInvalido ? "Bearer error=\"invalid_token\"" : "Bearer");
        respuesta.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        // Textos fijos, sin nada que venga de la petición: se puede armar el JSON a mano sin riesgo
        String cuerpo = """
                {"type":"about:blank","title":"No autorizado","status":401,"detail":"%s"}"""
                .formatted(tokenInvalido ? TOKEN_INVALIDO : SIN_TOKEN);
        DataBuffer buffer = respuesta.bufferFactory().wrap(cuerpo.getBytes(StandardCharsets.UTF_8));
        return respuesta.writeWith(Mono.just(buffer));
    }
}
