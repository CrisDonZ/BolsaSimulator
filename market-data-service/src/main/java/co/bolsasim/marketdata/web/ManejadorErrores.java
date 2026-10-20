package co.bolsasim.marketdata.web;

import co.bolsasim.marketdata.servicio.InstrumentoDuplicadoException;
import co.bolsasim.marketdata.servicio.InstrumentoInvalidoException;
import co.bolsasim.marketdata.servicio.InstrumentoNoEncontradoException;
import java.sql.SQLException;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Convierte los errores en respuestas claras (formato ProblemDetail, RFC 9457),
 * para que quien llama a la API sepa qué corregir (RNF-44).
 */
@RestControllerAdvice
public class ManejadorErrores {

    // Código de PostgreSQL para "llave duplicada"
    private static final String LLAVE_DUPLICADA = "23505";

    // Campos de un instrumento que existen pero no se editan: se pueden nombrar en la respuesta sin
    // repetir lo que mandó el cliente
    private static final Set<String> CAMPOS_FIJOS = Set.of("simbolo", "precioInicial", "tick", "accionesEmitidas");

    private static final String CAMPO_DESCONOCIDO = "El instrumento trae un campo que no existe. Los campos son: "
            + "simbolo, nombre, sector, precioInicial, tick y accionesEmitidas.";
    private static final String CUERPO_ILEGIBLE = "El cuerpo no es un instrumento válido en JSON. Revisa que "
            + "precioInicial, tick y accionesEmitidas sean números enteros.";

    @ExceptionHandler(InstrumentoDuplicadoException.class)
    public ProblemDetail duplicado(InstrumentoDuplicadoException e) {
        return problema(HttpStatus.CONFLICT, "Instrumento ya registrado", e.getMessage());
    }

    /**
     * Dos administradores crean el mismo símbolo al mismo tiempo: la llave primaria detiene al segundo.
     * Cualquier otra regla de la base que falle es un error nuestro, no del cliente.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail violaRestriccion(DataIntegrityViolationException e) {
        if (NestedExceptionUtils.getMostSpecificCause(e) instanceof SQLException sql
                && LLAVE_DUPLICADA.equals(sql.getSQLState())) {
            return problema(HttpStatus.CONFLICT, "Instrumento ya registrado", new InstrumentoDuplicadoException().getMessage());
        }
        throw e;
    }

    @ExceptionHandler(InstrumentoNoEncontradoException.class)
    public ProblemDetail noEncontrado(InstrumentoNoEncontradoException e) {
        return problema(HttpStatus.NOT_FOUND, "Instrumento no encontrado", e.getMessage());
    }

    @ExceptionHandler(InstrumentoInvalidoException.class)
    public ProblemDetail invalido(InstrumentoInvalidoException e) {
        return problema(HttpStatus.BAD_REQUEST, "Datos incompletos o inválidos", e.getMessage());
    }

    /**
     * El cuerpo no se pudo leer: no es JSON, un valor no es del tipo esperado o trae un campo que no
     * existe. Al editar, un campo que existe pero no se puede cambiar (como el tick) se explica.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail cuerpoIlegible(HttpMessageNotReadableException e) {
        String detalle = CUERPO_ILEGIBLE;
        for (Throwable causa = e; causa != null; causa = causa.getCause()) {
            if (causa instanceof UnrecognizedPropertyException campo) {
                detalle = explicarCampo(campo);
                break;
            }
        }
        return problema(HttpStatus.BAD_REQUEST, "Datos incompletos o inválidos", detalle);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail camposInvalidos(MethodArgumentNotValidException e) {
        String detalle = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return problema(HttpStatus.BAD_REQUEST, "Datos incompletos o inválidos", detalle);
    }

    private static String explicarCampo(UnrecognizedPropertyException campo) {
        String nombre = campo.getPropertyName();
        if ("estado".equals(nombre)) {
            return "estado no se envía: todo instrumento nace ACTIVO, y para cambiarlo están "
                    + "POST /api/instrumentos/{simbolo}/suspender y /reactivar.";
        }
        if (EditarInstrumentoRequest.class.equals(campo.getReferringClass()) && CAMPOS_FIJOS.contains(nombre)) {
            return nombre + " no se puede cambiar: después de crear un instrumento solo se editan nombre y sector.";
        }
        return EditarInstrumentoRequest.class.equals(campo.getReferringClass())
                ? "Solo se pueden editar nombre y sector."
                : CAMPO_DESCONOCIDO;
    }

    private static ProblemDetail problema(HttpStatus estado, String titulo, String detalle) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(estado, detalle);
        problema.setTitle(titulo);
        return problema;
    }
}
