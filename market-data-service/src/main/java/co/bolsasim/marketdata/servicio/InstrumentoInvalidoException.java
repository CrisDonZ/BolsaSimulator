package co.bolsasim.marketdata.servicio;

/** El instrumento no cumple una regla del negocio y no se guarda. */
public class InstrumentoInvalidoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InstrumentoInvalidoException(String mensaje) {
        super(mensaje);
    }
}
