package co.bolsasim.auth.servicio;

import java.util.UUID;

/** No hay ningún usuario con ese id. */
public class UsuarioNoEncontradoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UsuarioNoEncontradoException(UUID id) {
        super("No existe un usuario con id " + id + ".");
    }
}
