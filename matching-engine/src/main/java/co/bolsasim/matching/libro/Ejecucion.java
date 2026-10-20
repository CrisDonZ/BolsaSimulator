package co.bolsasim.matching.libro;

import java.util.UUID;

/**
 * Una compra y una venta que se cruzaron: el resultado de emparejar dos órdenes.
 *
 * <p>Además de la operación, dice cuánto lleva ejecutado cada orden después de ella: con eso el motor arma el
 * evento de cada orden en orders.events (PARCIAL o EJECUTADA).
 *
 * @param simbolo         acción negociada
 * @param precio          precio en COP al que se ejecutó (el de la orden que estaba esperando en el libro)
 * @param cantidad        número de acciones que cambiaron de dueño
 * @param ordenCompraId   orden del comprador
 * @param compradorId     usuario que compra
 * @param cantidadCompra  acciones que pide la orden de compra
 * @param ejecutadaCompra acciones ejecutadas de la compra, contando esta
 * @param ordenVentaId    orden del vendedor
 * @param vendedorId      usuario que vende
 * @param cantidadVenta   acciones que pide la orden de venta
 * @param ejecutadaVenta  acciones ejecutadas de la venta, contando esta
 */
public record Ejecucion(
        String simbolo,
        long precio,
        int cantidad,
        UUID ordenCompraId,
        String compradorId,
        int cantidadCompra,
        int ejecutadaCompra,
        UUID ordenVentaId,
        String vendedorId,
        int cantidadVenta,
        int ejecutadaVenta) {
}
