package co.bolsasim.order.web;

import co.bolsasim.order.servicio.InstrumentoNoDisponibleException;
import co.bolsasim.order.servicio.OrdenInvalidaException;
import co.bolsasim.order.servicio.UsuarioNoRegistradoException;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Convierte los errores en respuestas 400 claras (formato ProblemDetail, RFC 9457),
 * para que quien llama a la API sepa qué corregir.
 */
@RestControllerAdvice
public class ManejadorErrores {

    private static final String CAMPO_DESCONOCIDO =
            "La orden trae un campo que no existe. Los campos son: simbolo, lado, tipo, precioLimite y cantidad.";
    private static final String CUERPO_ILEGIBLE =
            "El cuerpo no es una orden válida en JSON. Revisa que estén todos los campos, los valores de lado "
                    + "(COMPRA o VENTA) y tipo (LIMITE o MERCADO), y que precioLimite y cantidad sean números.";

    @ExceptionHandler(OrdenInvalidaException.class)
    public ProblemDetail ordenInvalida(OrdenInvalidaException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problema.setTitle("Orden inválida");
        return problema;
    }

    /**
     * 422 y no 404: la URL existe y la petición está bien formada; lo que no se puede
     * procesar es un dato de adentro, el usuarioId.
     */
    @ExceptionHandler(UsuarioNoRegistradoException.class)
    public ProblemDetail usuarioNoRegistrado(UsuarioNoRegistradoException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        problema.setTitle("Usuario no registrado");
        return problema;
    }

    /** 422 por lo mismo que el usuario: la petición está bien formada, pero el símbolo no se puede negociar. */
    @ExceptionHandler(InstrumentoNoDisponibleException.class)
    public ProblemDetail instrumentoNoDisponible(InstrumentoNoDisponibleException e) {
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        problema.setTitle("Instrumento no disponible");
        return problema;
    }

    /**
     * El cuerpo no se pudo leer: no es JSON, un valor no es del tipo esperado o trae un campo que no
     * existe. No se repite nada de lo que mandó el cliente, solo qué corregir.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail cuerpoIlegible(HttpMessageNotReadableException e) {
        String detalle = CUERPO_ILEGIBLE;
        for (Throwable causa = e; causa != null; causa = causa.getCause()) {
            if (causa instanceof UnrecognizedPropertyException campoDesconocido) {
                detalle = "usuarioId".equals(campoDesconocido.getPropertyName())
                        ? "usuarioId ya no se envía: el usuario de la orden sale del token de acceso."
                        : CAMPO_DESCONOCIDO;
                break;
            }
        }
        ProblemDetail problema = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detalle);
        problema.setTitle("Datos incompletos o inválidos");
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
