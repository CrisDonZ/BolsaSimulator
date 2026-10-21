package co.bolsasim.portfolio.posicion;

import java.math.BigDecimal;

/**
 * Tabla POSICION del modelo entidad-relación: las acciones de un símbolo que tiene una cuenta.
 *
 * @param cantidadReservada acciones bloqueadas en órdenes de venta (llegan con HU-06)
 * @param costoPromedio     precio promedio de compra, con 4 decimales
 */
public record Posicion(String simbolo, int cantidad, int cantidadReservada, BigDecimal costoPromedio) {
}
