package co.bolsasim.order.servicio;

/** Un parámetro de la lista de órdenes (estado, tamano o despues) no es válido. */
public class ConsultaInvalidaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConsultaInvalidaException(String mensaje) {
        super(mensaje);
    }
}
