package co.bolsasim.marketdata.servicio;

/** Ya hay un instrumento con ese símbolo (HU-22). */
public class InstrumentoDuplicadoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InstrumentoDuplicadoException() {
        super("Ese símbolo ya existe");
    }
}
