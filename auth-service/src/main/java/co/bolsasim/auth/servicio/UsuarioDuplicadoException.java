package co.bolsasim.auth.servicio;

/** El correo o el alias ya pertenecen a otro usuario. */
public class UsuarioDuplicadoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UsuarioDuplicadoException(String mensaje) {
        super(mensaje);
    }
}
