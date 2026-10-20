package co.bolsasim.order.orden;

/**
 * Ciclo de vida de una orden (modelo entidad-relación).
 *
 * <ul>
 *   <li>PENDIENTE: guardada, esperando la reserva de fondos y entrar al libro.</li>
 *   <li>ABIERTA: en el libro, sin ejecutar.</li>
 *   <li>PARCIAL: en el libro, con una parte ejecutada.</li>
 *   <li>EJECUTADA, CANCELADA o RECHAZADA: terminada.</li>
 * </ul>
 *
 * <p>Por ahora toda orden queda PENDIENTE: los demás estados llegarán cuando el motor publique orders.events.
 */
public enum EstadoOrden {
    PENDIENTE,
    ABIERTA,
    PARCIAL,
    EJECUTADA,
    CANCELADA,
    RECHAZADA
}
