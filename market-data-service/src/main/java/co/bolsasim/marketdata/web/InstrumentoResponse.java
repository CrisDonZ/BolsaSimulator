package co.bolsasim.marketdata.web;

import co.bolsasim.eventos.EstadoInstrumento;
import co.bolsasim.marketdata.instrumento.Instrumento;
import java.time.Instant;

/** Un instrumento como lo devuelve la API. El último precio y la variación del día llegan con HU-14. */
public record InstrumentoResponse(
        String simbolo,
        String nombre,
        String sector,
        long precioInicial,
        long tick,
        long accionesEmitidas,
        EstadoInstrumento estado,
        Instant creadoEn) {

    public static InstrumentoResponse de(Instrumento instrumento) {
        return new InstrumentoResponse(instrumento.getSimbolo(), instrumento.getNombre(), instrumento.getSector(),
                instrumento.getPrecioInicial(), instrumento.getTick(), instrumento.getAccionesEmitidas(),
                instrumento.getEstado(), instrumento.getCreadoEn());
    }
}
