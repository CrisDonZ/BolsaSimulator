package co.bolsasim.eventos;

/**
 * La banda de ±10 % alrededor del precio de referencia de un instrumento (RF-16, RF-26). Una sola fórmula para
 * order-service, que valida con ella el precio de una orden LIMITE, y para el motor, que corta con ella el cruce de una
 * orden a MERCADO: las dos nunca difieren.
 *
 * <p>Con enteros y sin redondeos sorpresa: el mínimo es 90 % de la referencia redondeado hacia arriba y el máximo,
 * 110 % redondeado hacia abajo. Los límites cuentan: con referencia 5.000, 4.500 y 5.500 están dentro.
 *
 * <p>El máximo nunca supera referencia × 1,10, así una compra a mercado que reservó
 * ceil(cantidad × referencia × 1,10) siempre alcanza a pagar lo que la banda la deja ejecutar.
 */
public final class BandaPrecio {

    private BandaPrecio() {
    }

    /**
     * @throws IllegalArgumentException si la referencia no es positiva
     * @throws ArithmeticException      si la referencia es tan grande que el cálculo no cabe en un long
     */
    public static long minimo(long referencia) {
        validar(referencia);
        return Math.addExact(Math.multiplyExact(referencia, 9L), 9L) / 10;
    }

    /**
     * @throws IllegalArgumentException si la referencia no es positiva
     * @throws ArithmeticException      si la referencia es tan grande que el cálculo no cabe en un long
     */
    public static long maximo(long referencia) {
        validar(referencia);
        return Math.multiplyExact(referencia, 11L) / 10;
    }

    /** Si el precio está dentro de la banda de la referencia, con los límites incluidos. */
    public static boolean contiene(long referencia, long precio) {
        return precio >= minimo(referencia) && precio <= maximo(referencia);
    }

    private static void validar(long referencia) {
        if (referencia <= 0) {
            throw new IllegalArgumentException("El precio de referencia debe ser positivo: " + referencia);
        }
    }
}
