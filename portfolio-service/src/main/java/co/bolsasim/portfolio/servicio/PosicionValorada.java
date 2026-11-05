package co.bolsasim.portfolio.servicio;

import co.bolsasim.portfolio.posicion.PosicionConPrecio;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Una posición con lo que vale ahora y cuánto gana o pierde (RF-32). Los montos van en COP enteros, redondeados
 * al peso más cercano; el porcentaje, con 2 decimales.
 *
 * @param cantidad          todas las acciones, también las reservadas en ventas: siguen siendo del usuario
 * @param precioPromedio    precio promedio de compra, con 4 decimales
 * @param precioActual      el último precio del símbolo, o su precio inicial si todavía no tiene operaciones
 * @param costo             lo que costaron: cantidad × precio promedio
 * @param valorActual       lo que valen hoy: cantidad × precio actual
 * @param ganancia          valor actual − costo; negativa si pierde
 * @param gananciaPorcentaje ganancia / costo × 100
 */
public record PosicionValorada(
        String simbolo,
        long cantidad,
        long cantidadReservada,
        BigDecimal precioPromedio,
        long precioActual,
        long costo,
        long valorActual,
        long ganancia,
        BigDecimal gananciaPorcentaje) {

    static final BigDecimal CIEN = BigDecimal.valueOf(100);

    public static PosicionValorada de(PosicionConPrecio posicion) {
        long costo = posicion.costoPromedio().multiply(BigDecimal.valueOf(posicion.cantidad()))
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
        long valor = Math.multiplyExact(posicion.cantidad(), posicion.precioActual());
        long ganancia = valor - costo;
        // Un costo de 0 no pasa (todo precio es positivo), pero no se divide por él
        BigDecimal porcentaje = costo == 0 ? BigDecimal.ZERO.setScale(2)
                : BigDecimal.valueOf(ganancia).multiply(CIEN).divide(BigDecimal.valueOf(costo), 2, RoundingMode.HALF_UP);
        return new PosicionValorada(posicion.simbolo(), posicion.cantidad(), posicion.cantidadReservada(),
                posicion.costoPromedio(), posicion.precioActual(), costo, valor, ganancia, porcentaje);
    }
}
