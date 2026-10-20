package co.bolsasim.order.orden;

/**
 * Un evento de orders.events que no puede aplicarse a la orden: por ejemplo, llevaría la cantidad ejecutada por
 * encima de la cantidad (regla 8) o cambia una orden que ya terminó. Indica un error del motor, no del usuario.
 */
public class EventoImposibleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EventoImposibleException(String mensaje) {
        super(mensaje);
    }
}
