package co.bolsasim.portfolio.web;

import co.bolsasim.portfolio.servicio.PaginaMovimientos;
import java.util.List;

/**
 * Respuesta de GET /api/cuenta/movimientos. No trae el total: contarlo recorrería todos los movimientos en cada
 * página.
 *
 * @param siguiente el valor para el parámetro despues de la página siguiente; null si esta es la última
 */
public record PaginaMovimientosResponse(List<MovimientoResponse> movimientos, String siguiente) {

    public static PaginaMovimientosResponse de(PaginaMovimientos pagina) {
        return new PaginaMovimientosResponse(
                pagina.movimientos().stream().map(MovimientoResponse::de).toList(),
                pagina.siguiente() == null ? null : pagina.siguiente().codificar());
    }
}
