package co.bolsasim.portfolio.reserva;

import java.util.UUID;

/**
 * Tabla RESERVA del modelo entidad-relación: lo que una orden tiene bloqueado (RF-17). Por ahora solo hay reservas
 * de dinero, de las compras; las de acciones llegan con las ventas (HU-06).
 *
 * @param usuarioId      el dueño de la cuenta; no está en la tabla, sale de CUENTA
 * @param precioLimite   el precio máximo de una compra LIMITE; null en una compra a mercado
 * @param montoReservado lo que se bloqueó al aceptar la orden, en COP
 * @param montoConsumido lo ya pagado en operaciones
 * @param montoLiberado  lo ya devuelto al disponible
 */
public record Reserva(
        UUID id,
        UUID cuentaId,
        UUID usuarioId,
        UUID ordenId,
        Long precioLimite,
        long montoReservado,
        long montoConsumido,
        long montoLiberado,
        EstadoReserva estado) {

    /** Lo que sigue bloqueado: ni pagado ni devuelto. */
    public long pendiente() {
        return montoReservado - montoConsumido - montoLiberado;
    }
}
