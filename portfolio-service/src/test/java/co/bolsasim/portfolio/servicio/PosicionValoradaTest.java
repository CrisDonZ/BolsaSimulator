package co.bolsasim.portfolio.servicio;

import static org.assertj.core.api.Assertions.assertThat;

import co.bolsasim.portfolio.cuenta.Cuenta;
import co.bolsasim.portfolio.posicion.PosicionConPrecio;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** El valor, la ganancia y la pérdida de cada posición (RF-32), y el valor total del portafolio (RF-33). */
class PosicionValoradaTest {

    @Test
    void doscientasA5500QueHoyValen6000Ganan100000QueEsUn9Coma09PorCiento() {
        PosicionValorada posicion = PosicionValorada.de(
                new PosicionConPrecio("ANDX", 200, 0, new BigDecimal("5500.0000"), 6_000));

        assertThat(posicion.costo()).isEqualTo(1_100_000);
        assertThat(posicion.valorActual()).isEqualTo(1_200_000);
        assertThat(posicion.ganancia()).isEqualTo(100_000);
        assertThat(posicion.gananciaPorcentaje()).isEqualByComparingTo("9.09");
    }

    @Test
    void siElPrecioBajaLaGananciaEsNegativa() {
        PosicionValorada posicion = PosicionValorada.de(
                new PosicionConPrecio("ANDX", 100, 0, new BigDecimal("5000.0000"), 4_500));

        assertThat(posicion.ganancia()).isEqualTo(-50_000);
        assertThat(posicion.gananciaPorcentaje()).isEqualByComparingTo("-10.00");
    }

    @Test
    void elCostoSeRedondeaAlPesoMasCercano() {
        // 3 × 3.333,3333 = 9.999,9999 -> 10.000
        PosicionValorada posicion = PosicionValorada.de(
                new PosicionConPrecio("ANDX", 3, 0, new BigDecimal("3333.3333"), 3_333));

        assertThat(posicion.costo()).isEqualTo(10_000);
        assertThat(posicion.valorActual()).isEqualTo(9_999);
        assertThat(posicion.ganancia()).isEqualTo(-1);
        assertThat(posicion.gananciaPorcentaje()).isEqualByComparingTo("-0.01");
    }

    @Test
    void lasAccionesReservadasEnUnaVentaSiguenContando() {
        PosicionValorada posicion = PosicionValorada.de(
                new PosicionConPrecio("ANDX", 100, 40, new BigDecimal("5000.0000"), 5_000));

        assertThat(posicion.valorActual()).isEqualTo(500_000);
        assertThat(posicion.cantidadReservada()).isEqualTo(40);
    }

    @Test
    void elValorTotalEsElSaldoDisponibleMasElReservadoMasLasAcciones() {
        Cuenta cuenta = new Cuenta(UUID.randomUUID(), UUID.randomUUID(), 10_000_000, 8_000_000, 900_000, Instant.now());
        Portafolio portafolio = new Portafolio(cuenta, List.of(
                PosicionValorada.de(new PosicionConPrecio("ANDX", 100, 0, new BigDecimal("5000"), 5_200)),
                PosicionValorada.de(new PosicionConPrecio("TAYR", 10, 0, new BigDecimal("12000"), 11_000))));

        assertThat(portafolio.valorAcciones()).isEqualTo(520_000 + 110_000);
        assertThat(portafolio.valorTotal()).isEqualTo(8_900_000 + 630_000);
    }

    @Test
    void sinAccionesElValorTotalEsElSaldo() {
        Cuenta cuenta = new Cuenta(UUID.randomUUID(), UUID.randomUUID(), 10_000_000, 10_000_000, 0, Instant.now());

        assertThat(new Portafolio(cuenta, List.of()).valorTotal()).isEqualTo(10_000_000);
    }
}
