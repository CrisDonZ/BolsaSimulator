package co.bolsasim.portfolio.servicio;

/**
 * Un evento de orders.events que no puede liquidarse: por ejemplo, cambia una reserva que ya terminó, es de otro
 * usuario o se ejecutó por encima del precio límite. Indica un error de otro servicio, no del usuario.
 */
public class EventoImposibleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EventoImposibleException(String mensaje) {
        super(mensaje);
    }
}
