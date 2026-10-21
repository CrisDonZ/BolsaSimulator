package co.bolsasim.eventos;

import java.time.Instant;
import java.util.UUID;

/**
 * Mensaje que order-service publica en {@link Topicos#ORDERS_REQUESTED} por cada orden nueva: le pide a
 * portfolio-service que reserve el dinero o las acciones antes de que la orden llegue al motor (RF-17). La
 * respuesta llega en {@link Topicos#RESERVATIONS_EVENTS}.
 *
 * <p>Lleva la orden completa: portfolio-service calcula cuánto reservar con sus propias reglas. El último precio
 * lo pone order-service, que es quien lo conoce, y es el mismo con el que validó el precio de la orden.
 *
 * @param solicitudId  identificador del mensaje (sirve para detectar duplicados)
 * @param ordenId      la orden que pide la reserva
 * @param usuarioId    dueño de la orden; es la clave del mensaje
 * @param simbolo      acción que se negocia, en mayúsculas
 * @param lado         COMPRA reserva dinero; VENTA, acciones
 * @param tipo         MERCADO o LIMITE
 * @param precioLimite precio en COP; null si la orden es a MERCADO
 * @param cantidad     número de acciones, mayor que cero
 * @param ultimoPrecio último precio del instrumento en COP; con él se reserva una compra a mercado
 * @param solicitadaEn momento en que se creó la orden
 */
public record SolicitudReserva(
        UUID solicitudId,
        UUID ordenId,
        String usuarioId,
        String simbolo,
        Lado lado,
        TipoOrden tipo,
        Long precioLimite,
        int cantidad,
        long ultimoPrecio,
        Instant solicitadaEn) {

    /** La solicitud de la orden de ese comando NUEVA, con un solicitudId nuevo. */
    public static SolicitudReserva de(ComandoOrden comando, long ultimoPrecio) {
        return new SolicitudReserva(UUID.randomUUID(), comando.ordenId(), comando.usuarioId(), comando.simbolo(),
                comando.lado(), comando.tipo(), comando.precioLimite(), comando.cantidad(), ultimoPrecio,
                comando.enviadoEn());
    }
}
