package co.bolsasim.portfolio.web;

import co.bolsasim.portfolio.movimiento.Movimiento;
import co.bolsasim.portfolio.movimiento.TipoMovimiento;
import java.time.Instant;
import java.util.UUID;

/**
 * Un movimiento de dinero como lo ve su dueño (RF-09). No lleva la cuenta: siempre es la de quien pregunta.
 *
 * @param monto        en COP: positivo entra, negativo sale
 * @param referenciaId la orden u operación de origen; null en el saldo inicial
 */
public record MovimientoResponse(
        UUID id,
        TipoMovimiento tipo,
        long monto,
        String simbolo,
        Integer cantidad,
        UUID referenciaId,
        Instant creadoEn) {

    public static MovimientoResponse de(Movimiento movimiento) {
        return new MovimientoResponse(movimiento.id(), movimiento.tipo(), movimiento.monto(), movimiento.simbolo(),
                movimiento.cantidad(), movimiento.referenciaId(), movimiento.creadoEn());
    }
}
