package co.bolsasim.eventos;

/**
 * Ciclo de vida de una orden (modelo entidad-relación). Lo comparten order-service, que guarda la orden, y el
 * motor, que publica sus cambios en {@link Topicos#ORDERS_EVENTS}. RECHAZADA es el único que no sale del motor: lo
 * decide la reserva de portfolio-service ({@link Topicos#RESERVATIONS_EVENTS}).
 *
 * <ul>
 *   <li>PENDIENTE: guardada en order-service, esperando su reserva o la respuesta del motor.</li>
 *   <li>ABIERTA: en el libro, sin ejecutar.</li>
 *   <li>PARCIAL: en el libro, con una parte ejecutada.</li>
 *   <li>EJECUTADA: ejecutada completa.</li>
 *   <li>CANCELADA: retirada del libro, o lo que no se ejecutó de una orden a mercado (RF-26); puede tener una
 *       parte ejecutada.</li>
 *   <li>RECHAZADA: sin fondos o acciones para reservar (RF-18); nunca llegó al motor.</li>
 * </ul>
 */
public enum EstadoOrden {
    PENDIENTE,
    ABIERTA,
    PARCIAL,
    EJECUTADA,
    CANCELADA,
    RECHAZADA;

    /** EJECUTADA, CANCELADA o RECHAZADA: la orden ya no cambia. */
    public boolean terminado() {
        return this == EJECUTADA || this == CANCELADA || this == RECHAZADA;
    }
}
