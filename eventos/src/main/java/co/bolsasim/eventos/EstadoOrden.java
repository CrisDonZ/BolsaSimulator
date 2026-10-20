package co.bolsasim.eventos;

/**
 * Ciclo de vida de una orden (modelo entidad-relación). Lo comparten order-service, que guarda la orden, y el
 * motor, que publica sus cambios en {@link Topicos#ORDERS_EVENTS}.
 *
 * <ul>
 *   <li>PENDIENTE: guardada en order-service, todavía sin noticias del motor.</li>
 *   <li>ABIERTA: en el libro, sin ejecutar.</li>
 *   <li>PARCIAL: en el libro, con una parte ejecutada.</li>
 *   <li>EJECUTADA: ejecutada completa.</li>
 *   <li>CANCELADA: retirada del libro, o lo que no se ejecutó de una orden a mercado (RF-26); puede tener una
 *       parte ejecutada.</li>
 *   <li>RECHAZADA: sin fondos o acciones para reservar (llegará con portfolio-service).</li>
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
