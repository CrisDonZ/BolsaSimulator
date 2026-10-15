package co.bolsasim.order.web;

import co.bolsasim.order.servicio.OrdenInvalidaException;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Convierte los errores en respuestas 400 claras (formato ProblemDetail, RFC 9457),
 * para que quien llama a la API sepa qué corregir.
 */
@RestControllerAdvice
public class ManejadorErrores {

    @ExceptionHandler(OrdenInvalidaException.class)
    public ProblemDetail ordenInvalida(OrdenInvalidaException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problema.setTitle("Orden inválida");
        return problema;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail camposInvalidos(MethodArgumentNotValidException e) {
        String detalle = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detalle);
        problema.setTitle("Datos incompletos o inválidos");
        return problema;
    }
}
