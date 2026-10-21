package co.bolsasim.order.servicio;

/**
 * El precio límite no es múltiplo del tick o está a más de 10 % del último precio (RF-16). La orden no se guarda
 * ni se envía.
 */
public class PrecioInvalidoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PrecioInvalidoException(String mensaje) {
        super(mensaje);
    }
}
