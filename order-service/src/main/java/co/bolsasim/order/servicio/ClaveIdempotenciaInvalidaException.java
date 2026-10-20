package co.bolsasim.order.servicio;

/** Falta el header Idempotency-Key o no tiene el formato que se acepta (HU-10). */
public class ClaveIdempotenciaInvalidaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ClaveIdempotenciaInvalidaException(String mensaje) {
        super(mensaje);
    }
}
