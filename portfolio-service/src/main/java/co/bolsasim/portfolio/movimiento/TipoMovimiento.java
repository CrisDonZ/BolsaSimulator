package co.bolsasim.portfolio.movimiento;

/**
 * De dónde sale cada movimiento de dinero (RF-09). Por ahora solo hay SALDO_INICIAL; RESERVA, LIBERACION,
 * COMPRA y VENTA llegarán con las reservas y la liquidación (HU-05), y REINICIO con HU-25.
 */
public enum TipoMovimiento {
    SALDO_INICIAL,
    RESERVA,
    LIBERACION,
    COMPRA,
    VENTA,
    REINICIO
}
