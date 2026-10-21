package co.bolsasim.portfolio.posicion;

import java.math.BigDecimal;

/**
 * Tabla POSICION del modelo entidad-relación: las acciones de un símbolo que tiene una cuenta.
 *
 * @param cantidadReservada acciones bloqueadas en órdenes de venta
 * @param costoPromedio     precio promedio de compra, con 4 decimales; vender no lo cambia
 */
public record Posicion(String simbolo, long cantidad, long cantidadReservada, BigDecimal costoPromedio) {

    /** Las que se pueden vender: las que no están reservadas en otra venta. */
    public long libres() {
        return cantidad - cantidadReservada;
    }
}
