package co.bolsasim.portfolio.movimiento;

/**
 * De dónde sale cada movimiento de dinero (RF-09).
 *
 * <ul>
 *   <li>SALDO_INICIAL: el dinero con el que se abre la cuenta.</li>
 *   <li>RESERVA (negativo) y LIBERACION (positivo): dinero que pasa del disponible al reservado de una orden, y
 *       de vuelta. No cambian el total de la cuenta.</li>
 *   <li>COMPRA (negativo) y VENTA (positivo): dinero que sale de la cuenta o entra en ella por una operación. La
 *       compra se paga desde el reservado.</li>
 *   <li>REINICIO: llega con HU-25. VENTA llega con la liquidación del vendedor (HU-06).</li>
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
    REINICIO
}
