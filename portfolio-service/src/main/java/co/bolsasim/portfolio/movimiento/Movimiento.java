package co.bolsasim.portfolio.movimiento;

import java.time.Instant;
import java.util.UUID;

/**
 * Tabla MOVIMIENTO del modelo entidad-relación: una entrada o salida de dinero de la cuenta.
 *
 * @param monto        en COP: positivo entra, negativo sale
 * @param simbolo      la acción, si el movimiento es de una compra o una venta; null en los demás
 * @param cantidad     las acciones, si aplica; null en los demás
 * @param referenciaId la orden u operación de origen; null en el saldo inicial
 */
public record Movimiento(
        UUID id,
        UUID cuentaId,
        TipoMovimiento tipo,
        long monto,
        String simbolo,
        Long cantidad,
        UUID referenciaId,
        Instant creadoEn) {
}
