package co.bolsasim.marketdata.servicio;

public class InstrumentoNoEncontradoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InstrumentoNoEncontradoException(String simbolo) {
        super("El instrumento " + simbolo + " no existe.");
    }
}
