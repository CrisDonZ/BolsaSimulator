package co.bolsasim.portfolio.servicio;

/** Un parámetro de la lista de movimientos (tipo, desde, hasta, tamano o despues) no es válido. */
public class ConsultaInvalidaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConsultaInvalidaException(String mensaje) {
        super(mensaje);
    }
}
