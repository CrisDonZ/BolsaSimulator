package co.bolsasim.portfolio.posicion;

import java.math.BigDecimal;

/**
 * Una posición con el precio al que se valora ahora (ver {@link RepositorioPosiciones#deLaCuenta}).
 *
 * @param costoPromedio precio promedio de compra, con 4 decimales
 * @param precioActual  precio de la última operación del símbolo o, si no hay ninguna, su precio inicial, en COP
 */
public record PosicionConPrecio(String simbolo, long cantidad, long cantidadReservada, BigDecimal costoPromedio,
                                long precioActual) {
}
