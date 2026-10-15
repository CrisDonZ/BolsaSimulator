package co.bolsasim.order.servicio;

/** El usuarioId de la orden no corresponde a ningún usuario registrado en auth-service. */
public class UsuarioNoRegistradoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UsuarioNoRegistradoException(String usuarioId) {
        super("El usuario " + usuarioId + " no está registrado. "
                + "Si acabas de registrarte, intenta de nuevo en unos segundos.");
    }
}
