package co.bolsasim.marketdata.web;

import co.bolsasim.eventos.EstadoInstrumento;
import co.bolsasim.marketdata.operacion.Cotizacion;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Un instrumento como lo devuelve la API, con sus precios del día (HU-14). Precios y variación en COP enteros;
 * variacionPorcentaje, con 2 decimales.
 */
public record InstrumentoResponse(
        String simbolo,
        String nombre,
        String sector,
        long precioInicial,
        long tick,
        long accionesEmitidas,
        EstadoInstrumento estado,
        Instant creadoEn,
        long ultimoPrecio,
        long precioApertura,
        long variacion,
        BigDecimal variacionPorcentaje) {

    public static InstrumentoResponse de(Cotizacion cotizacion) {
        return new InstrumentoResponse(cotizacion.simbolo(), cotizacion.nombre(), cotizacion.sector(),
                cotizacion.precioInicial(), cotizacion.tick(), cotizacion.accionesEmitidas(), cotizacion.estado(),
                cotizacion.creadoEn(), cotizacion.ultimoPrecio(), cotizacion.precioApertura(), cotizacion.variacion(),
                cotizacion.variacionPorcentaje());
    }
}
