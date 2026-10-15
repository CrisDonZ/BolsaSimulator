package co.bolsasim.auth.web;

import co.bolsasim.auth.servicio.UsuarioDuplicadoException;
import co.bolsasim.auth.servicio.UsuarioNoEncontradoException;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Convierte los errores en respuestas claras (formato ProblemDetail, RFC 9457),
 * para que quien llama a la API sepa qué corregir (RNF-44).
 */
@RestControllerAdvice
public class ManejadorErrores {

    @ExceptionHandler(UsuarioDuplicadoException.class)
    public ProblemDetail duplicado(UsuarioDuplicadoException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problema.setTitle("Usuario ya registrado");
        return problema;
    }

    /** Dos registros iguales al mismo tiempo: la restricción UNIQUE de la base detiene al segundo. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail violaRestriccion(DataIntegrityViolationException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "El correo o el alias ya están registrados.");
        problema.setTitle("Usuario ya registrado");
        return problema;
    }

    @ExceptionHandler(UsuarioNoEncontradoException.class)
    public ProblemDetail noEncontrado(UsuarioNoEncontradoException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problema.setTitle("Usuario no encontrado");
        return problema;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail camposInvalidos(MethodArgumentNotValidException e) {
        String detalle = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detalle);
        problema.setTitle("Datos incompletos o inválidos");
        return problema;
    }
}
