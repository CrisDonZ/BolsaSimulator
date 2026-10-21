package co.bolsasim.eventos;

/**
 * ACEPTADA: portfolio-service reservó lo necesario y la orden puede ir al motor.
 * RECHAZADA: no hay saldo o acciones suficientes; la orden queda RECHAZADA con el motivo (RF-18).
 */
public enum ResultadoReserva {
    ACEPTADA,
    RECHAZADA
}
