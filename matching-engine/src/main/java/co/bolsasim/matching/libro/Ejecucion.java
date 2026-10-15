package co.bolsasim.matching.libro;

import java.util.UUID;

/**
 * Una compra y una venta que se cruzaron: el resultado de emparejar dos órdenes.
 *
 * @param simbolo       acción negociada
 * @param precio        precio en COP al que se ejecutó (el de la orden que estaba esperando en el libro)
 * @param cantidad      número de acciones que cambiaron de dueño
 * @param ordenCompraId orden del comprador
 * @param compradorId   usuario que compra
 * @param ordenVentaId  orden del vendedor
 * @param vendedorId    usuario que vende
 */
public record Ejecucion(
        String simbolo,
        long precio,
        int cantidad,
        UUID ordenCompraId,
        String compradorId,
        UUID ordenVentaId,
        String vendedorId) {
}
