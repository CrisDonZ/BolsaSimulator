package co.bolsasim.auth.servicio;

/** El token de refresco no existe, venció o la sesión ya se cerró: hay que iniciar sesión de nuevo. */
public class TokenRefrescoInvalidoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TokenRefrescoInvalidoException() {
        super("La sesión venció o ya se cerró. Inicia sesión de nuevo.");
    }
}
