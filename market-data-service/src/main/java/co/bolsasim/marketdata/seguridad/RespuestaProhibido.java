package co.bolsasim.marketdata.seguridad;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Respuesta 403 en formato ProblemDetail (RFC 9457) para quien tiene un token válido pero no es ADMIN.
 *
 * <p>403 y no 404 (como en el perfil de otro usuario): el catálogo lo ve cualquiera y la ruta de
 * administración no es un secreto, así que un 404 solo confundiría a quien la usa.
 */
@Component
public class RespuestaProhibido implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(RespuestaProhibido.class);

    @Override
    public void handle(HttpServletRequest peticion, HttpServletResponse respuesta, AccessDeniedException error)
            throws IOException {
        log.info("403 en {} {}: el usuario no es ADMIN", peticion.getMethod(), peticion.getRequestURI());

        respuesta.setStatus(HttpServletResponse.SC_FORBIDDEN);
        respuesta.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        respuesta.setCharacterEncoding(StandardCharsets.UTF_8.name());

        // Texto fijo, sin nada que venga de la petición: se puede armar el JSON a mano sin riesgo
        respuesta.getWriter().write("""
                {"type":"about:blank","title":"Sin permiso","status":403,\
                "detail":"Solo un administrador puede gestionar instrumentos."}""");
    }
}
