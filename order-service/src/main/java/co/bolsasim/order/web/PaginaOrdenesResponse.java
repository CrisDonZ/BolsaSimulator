package co.bolsasim.order.web;

import co.bolsasim.order.servicio.PaginaOrdenes;
import java.util.List;

/**
 * Respuesta de GET /api/ordenes. Un record propio y no el Page de Spring, cuyo JSON puede cambiar entre
 * versiones. No trae el total: contarlo recorrería todas las órdenes del usuario en cada página.
 *
 * @param siguiente el valor para el parámetro despues de la página siguiente; null si esta es la última
 */
public record PaginaOrdenesResponse(List<OrdenResponse> ordenes, String siguiente) {

    public static PaginaOrdenesResponse de(PaginaOrdenes pagina) {
        return new PaginaOrdenesResponse(
                pagina.ordenes().stream().map(OrdenResponse::de).toList(),
                pagina.siguiente() == null ? null : pagina.siguiente().codificar());
    }
}
