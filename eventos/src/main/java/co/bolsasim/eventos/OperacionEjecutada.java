package co.bolsasim.eventos;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento que el motor publica en {@link Topicos#TRADES_EXECUTED} por cada operación: una compra y una venta que
 * se cruzaron (RF-28). Lo consume order-service, para el último precio con el que valida las órdenes (RF-16), y lo
 * consumirá market-data-service, para el último precio y las velas. portfolio-service liquida con
 * {@link EventoOrden}, que lleva la parte de cada orden en el mismo orden que su cancelación. Son los campos de OPERACION del modelo entidad-relación, salvo la secuencia, que
 * asigna market-data-service al guardarla: el orden ya lo da la partición (clave = símbolo).
 *
 * @param operacionId identificador de la operación; sale del comando que la produjo (ver {@link EventoOrden})
 * @param simbolo     acción negociada; es la clave del mensaje
 * @param precio      precio en COP: el de la orden que ya estaba en el libro
 * @param cantidad    acciones que cambiaron de dueño
 * @param ejecutadaEn momento de la operación
 */
public record OperacionEjecutada(
        UUID operacionId,
        String simbolo,
        long precio,
        int cantidad,
        UUID ordenCompraId,
        String compradorId,
        UUID ordenVentaId,
        String vendedorId,
        Instant ejecutadaEn) {
}
