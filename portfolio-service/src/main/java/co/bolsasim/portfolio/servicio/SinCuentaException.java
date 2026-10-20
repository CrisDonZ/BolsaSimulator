package co.bolsasim.portfolio.servicio;

/** Un ADMIN consulta una cuenta: no tiene, porque no opera. */
public class SinCuentaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SinCuentaException() {
        super("Los administradores no tienen cuenta de inversión.");
    }
}
