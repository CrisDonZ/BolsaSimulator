package co.bolsasim.order.servicio;

import java.util.UUID;

/** La orden no existe o es de otro usuario: las dos dan el mismo mensaje. */
public class OrdenNoEncontradaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OrdenNoEncontradaException(UUID ordenId) {
        super("No hay ninguna orden tuya con el id " + ordenId + ".");
    }
}
