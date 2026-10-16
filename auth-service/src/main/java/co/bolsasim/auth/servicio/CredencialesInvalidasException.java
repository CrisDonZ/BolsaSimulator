package co.bolsasim.auth.servicio;

/**
 * El correo no existe o la contraseña no coincide. El mensaje es el mismo en los dos casos
 * para no revelar qué correos tienen cuenta (HU-02).
 */
public class CredencialesInvalidasException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CredencialesInvalidasException() {
        super("Correo o contraseña incorrectos");
    }
}
