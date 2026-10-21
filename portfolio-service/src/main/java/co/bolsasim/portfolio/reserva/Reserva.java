package co.bolsasim.portfolio.reserva;

import java.util.UUID;

/**
 * Tabla RESERVA del modelo entidad-relación: lo que una orden tiene bloqueado (RF-17). Una compra reserva dinero;
 * una venta, acciones.
 *
 * @param usuarioId      el dueño de la cuenta; no está en la tabla, sale de CUENTA
 * @param simbolo        la acción reservada; null si se reserva dinero
 * @param precioLimite   el precio máximo de una compra LIMITE; null en una compra a mercado y en una venta
 * @param montoReservado lo que se bloqueó al aceptar la orden: COP o acciones, según el tipo
 * @param montoConsumido lo ya pagado (compra) o entregado (venta) en operaciones
 * @param montoLiberado  lo ya devuelto: al disponible (compra) o a las acciones libres (venta)
 */
public record Reserva(
        UUID id,
        UUID cuentaId,
        UUID usuarioId,
        UUID ordenId,
        TipoReserva tipo,
        String simbolo,
        Long precioLimite,
        long montoReservado,
        long montoConsumido,
        long montoLiberado,
        EstadoReserva estado) {

    /** Lo que sigue bloqueado: ni usado ni devuelto. */
    public long pendiente() {
        return montoReservado - montoConsumido - montoLiberado;
    }
}
