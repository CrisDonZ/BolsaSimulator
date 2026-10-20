package co.bolsasim.order.servicio;

import co.bolsasim.order.orden.Cursor;
import co.bolsasim.order.orden.Orden;
import java.util.List;

/**
 * Una página de la lista de órdenes.
 *
 * @param siguiente dónde empieza la página siguiente; null si esta es la última
 */
public record PaginaOrdenes(List<Orden> ordenes, Cursor siguiente) {
}
