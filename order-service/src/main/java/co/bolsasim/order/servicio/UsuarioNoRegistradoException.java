package co.bolsasim.order.servicio;

/** El usuario del token ("sub") no está en la copia local de usuarios registrados en auth-service. */
public class UsuarioNoRegistradoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UsuarioNoRegistradoException(String usuarioId) {
        super("El usuario " + usuarioId + " no está registrado. "
                + "Si acabas de registrarte, intenta de nuevo en unos segundos.");
    }
}
