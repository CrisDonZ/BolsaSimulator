package co.bolsasim.order.servicio;

/** La orden no cumple una regla del negocio y no se envía al motor. */
public class OrdenInvalidaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OrdenInvalidaException(String mensaje) {
        super(mensaje);
    }
}
