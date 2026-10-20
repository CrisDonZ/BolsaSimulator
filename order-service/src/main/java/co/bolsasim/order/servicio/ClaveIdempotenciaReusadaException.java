package co.bolsasim.order.servicio;

/** La misma Idempotency-Key llegó con una orden distinta de la que ya se creó con ella (HU-10). */
public class ClaveIdempotenciaReusadaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ClaveIdempotenciaReusadaException() {
        super("Esa Idempotency-Key ya se usó para otra orden: usa una clave nueva para cada orden.");
    }
}
