package co.bolsasim.portfolio.reserva;

/**
 * ACTIVA: la orden sigue viva y lo reservado está bloqueado.
 * CONSUMIDA: la orden terminó con algo ejecutado; lo que no se pagó ya volvió al disponible.
 * LIBERADA: la orden terminó sin ejecutar nada (por ejemplo, se canceló) y todo volvió al disponible.
 */
public enum EstadoReserva {
    ACTIVA,
    CONSUMIDA,
    LIBERADA
}
