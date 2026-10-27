package co.bolsasim.matching.motor;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;

/** Montos en COP como los lee una persona en Colombia: 1.000.000, con punto de miles. */
final class Pesos {

    private Pesos() {
    }

    static String formato(long monto) {
        DecimalFormatSymbols simbolos = new DecimalFormatSymbols();
        simbolos.setGroupingSeparator('.');
        // DecimalFormat no es seguro entre hilos: uno nuevo por llamada
        return new DecimalFormat("#,##0", simbolos).format(monto);
    }
}
