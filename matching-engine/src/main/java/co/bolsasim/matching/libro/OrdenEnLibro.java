package co.bolsasim.matching.libro;

import co.bolsasim.eventos.Lado;
import java.util.UUID;

/**
 * Una orden LIMITE que espera en el libro a que llegue una contraparte.
 *
 * <p>A diferencia de {@code ComandoOrden}, no es un record: su cantidad pendiente
 * baja cada vez que se ejecuta una parte de ella.
 */
final class OrdenEnLibro {

    final UUID ordenId;
    final String usuarioId;
    final Lado lado;
    final long precio;
    // Lo que pidió la orden al llegar; con el pendiente da lo ejecutado, que va en cada evento
    final int cantidad;
    int pendiente;

    OrdenEnLibro(UUID ordenId, String usuarioId, Lado lado, long precio, int cantidad, int pendiente) {
        this.ordenId = ordenId;
        this.usuarioId = usuarioId;
        this.lado = lado;
        this.precio = precio;
        this.cantidad = cantidad;
        this.pendiente = pendiente;
    }

    int ejecutada() {
        return cantidad - pendiente;
    }
}
