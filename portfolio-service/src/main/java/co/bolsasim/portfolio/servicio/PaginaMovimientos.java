package co.bolsasim.portfolio.servicio;

import co.bolsasim.portfolio.movimiento.Cursor;
import co.bolsasim.portfolio.movimiento.Movimiento;
import java.util.List;

/**
 * Una página del historial de movimientos.
 *
 * @param siguiente dónde empieza la página siguiente; null si esta es la última
 */
public record PaginaMovimientos(List<Movimiento> movimientos, Cursor siguiente) {
}
