package co.bolsasim.portfolio.reserva;

/**
 * DINERO: la reserva de una compra, en COP; cuenta en el saldo_reservado de la cuenta.
 * ACCIONES: la reserva de una venta, en acciones; cuenta en la cantidad_reservada de la posición.
 */
public enum TipoReserva {
    DINERO,
    ACCIONES
}
