package co.bolsasim.portfolio.web;

import co.bolsasim.portfolio.servicio.ConsultaInvalidaException;
import co.bolsasim.portfolio.servicio.SinCuentaException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Convierte los errores en respuestas claras (formato ProblemDetail, RFC 9457),
 * para que quien llama a la API sepa qué corregir (RNF-44).
 */
@RestControllerAdvice
public class ManejadorErrores {

    @ExceptionHandler(SinCuentaException.class)
    public ProblemDetail sinCuenta(SinCuentaException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problema.setTitle("Sin cuenta");
        return problema;
    }

    @ExceptionHandler(ConsultaInvalidaException.class)
    public ProblemDetail consultaInvalida(ConsultaInvalidaException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problema.setTitle("Consulta inválida");
        return problema;
    }

    /** Un tamano que no es un número. Solo se nombra el parámetro, que es nuestro. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail parametroInvalido(MethodArgumentTypeMismatchException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                e.getName() + " no tiene el formato esperado.");
        problema.setTitle("Consulta inválida");
        return problema;
    }
}
