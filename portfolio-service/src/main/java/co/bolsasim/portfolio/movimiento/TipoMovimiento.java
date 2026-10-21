package co.bolsasim.portfolio.movimiento;

/**
 * De dónde sale cada movimiento de la cuenta (RF-09).
 *
 * <ul>
 *   <li>SALDO_INICIAL: el dinero con el que se abre la cuenta.</li>
 *   <li>RESERVA (negativo) y LIBERACION (positivo): dinero que pasa del disponible al reservado de una compra, y
 *       de vuelta. No cambian el total de la cuenta. Las reservas de acciones de una venta no son movimientos de
 *       dinero: se ven en la posición.</li>
 *   <li>COMPRA (negativo) y VENTA (positivo): dinero que sale de la cuenta o entra en ella por una operación. La
 *       compra se paga desde el reservado; la venta entra al disponible.</li>
 *   <li>EMISION (monto 0): las acciones emitidas de un instrumento, que recibe el creador de mercado (RF-59). No
 *       mueve dinero; lleva el símbolo y la cantidad.</li>
 *   <li>REINICIO: llega con HU-25.</li>
 * </ul>
 *
 * <p>Así, SALDO_INICIAL + COMPRA + VENTA es el saldo total de la cuenta.
 */
public enum TipoMovimiento {
    SALDO_INICIAL,
    RESERVA,
    LIBERACION,
    COMPRA,
    VENTA,
    EMISION,
    REINICIO
}
