package co.bolsasim.marketdata.operacion;

import static org.assertj.core.api.Assertions.assertThat;

import co.bolsasim.eventos.EstadoInstrumento;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Prueba unitaria de la variación del día (RF-40): último precio frente al precio de apertura. */
class CotizacionTest {

    @ParameterizedTest(name = "de {0} a {1}: {2} COP, {3} %")
    @CsvSource({
            "5000,  5250,   250,   5.00",
            "5000,  4500,  -500, -10.00",
            "5000,  5000,     0,   0.00",
            // 10 / 3000 = 0,3333... % → 0,33
            "3000,  3010,    10,   0.33",
            // 20 / 3000 = 0,6666... % → 0,67
            "3000,  3020,    20,   0.67",
            // 50 / 40000 = 0,125 % → 0,13: la mitad se aleja de cero (HALF_UP), también en negativo
            "40000, 40050,   50,   0.13",
            "40000, 39950,  -50,  -0.13",
            "12000, 13200, 1200,  10.00"})
    void laVariacionEsLaDiferenciaConLaAperturaYSuPorcentajeVaConDosDecimales(long apertura, long ultimo,
                                                                            long variacion, String porcentaje) {
        Cotizacion cotizacion = cotizacion(ultimo, apertura);

        assertThat(cotizacion.variacion()).isEqualTo(variacion);
        // Como texto: también se comprueba que salgan 2 decimales (0.00, no 0)
        assertThat(cotizacion.variacionPorcentaje()).hasToString(porcentaje);
    }

    @Test
    void elPorcentajeSeMideContraLaAperturaNoContraElPrecioInicial() {
        // Empezó en 5.000, ayer cerró en 8.000 y hoy va en 8.400: sube 5 %, no 68 %
        Cotizacion cotizacion = cotizacion(8_400, 8_000);

        assertThat(cotizacion.precioInicial()).isEqualTo(5_000);
        assertThat(cotizacion.variacion()).isEqualTo(400);
        assertThat(cotizacion.variacionPorcentaje()).hasToString("5.00");
    }

    private static Cotizacion cotizacion(long ultimoPrecio, long precioApertura) {
        return new Cotizacion("ANDX", "Andex Energía", "Energía", 5_000, 10, 1_000_000, EstadoInstrumento.ACTIVO,
                Instant.now(), ultimoPrecio, precioApertura);
    }
}
