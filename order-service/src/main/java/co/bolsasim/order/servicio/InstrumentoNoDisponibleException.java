package co.bolsasim.order.servicio;

/** El símbolo de la orden no está en la copia local de instrumentos, o está suspendido (RF-16, RF-29). */
public class InstrumentoNoDisponibleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private InstrumentoNoDisponibleException(String mensaje) {
        super(mensaje);
    }

    public static InstrumentoNoDisponibleException noExiste(String simbolo) {
        return new InstrumentoNoDisponibleException("El instrumento " + simbolo + " no existe.");
    }

    public static InstrumentoNoDisponibleException suspendido(String simbolo) {
        return new InstrumentoNoDisponibleException(
                "El instrumento " + simbolo + " está suspendido: no recibe órdenes nuevas.");
    }
}
