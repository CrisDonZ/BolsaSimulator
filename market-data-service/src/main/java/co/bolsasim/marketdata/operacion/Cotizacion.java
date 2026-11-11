package co.bolsasim.marketdata.operacion;

import co.bolsasim.eventos.EstadoInstrumento;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Un instrumento con sus precios del día (HU-14): el último precio (RF-11) y el de apertura, contra el que se mide
 * la variación (RF-40).
 *
 * @param ultimoPrecio   el de la última operación; sin operaciones, el precio inicial
 * @param precioApertura el de la última operación anterior al comienzo del día; sin ninguna, el precio inicial
 */
public record Cotizacion(
        String simbolo,
        String nombre,
        String sector,
        long precioInicial,
        long tick,
        long accionesEmitidas,
        EstadoInstrumento estado,
        Instant creadoEn,
        long ultimoPrecio,
        long precioApertura) {

    /** Variación del día en COP: positiva si subió, negativa si bajó, 0 si no ha operado hoy. */
    public long variacion() {
        return ultimoPrecio - precioApertura;
    }

    /** La variación como porcentaje del precio de apertura, con 2 decimales: de 5.000 a 5.250 es 5.00. */
    public BigDecimal variacionPorcentaje() {
        return BigDecimal.valueOf(variacion() * 100)
                .divide(BigDecimal.valueOf(precioApertura), 2, RoundingMode.HALF_UP);
    }
}
