package co.bolsasim.eventos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BandaPrecioTest {

    @Test
    void conReferencia5000LaBandaVaDe4500A5500() {
        assertThat(BandaPrecio.minimo(5_000)).isEqualTo(4_500);
        assertThat(BandaPrecio.maximo(5_000)).isEqualTo(5_500);
        assertThat(BandaPrecio.contiene(5_000, 4_500)).isTrue();
        assertThat(BandaPrecio.contiene(5_000, 5_500)).isTrue();
        assertThat(BandaPrecio.contiene(5_000, 4_499)).isFalse();
        assertThat(BandaPrecio.contiene(5_000, 5_501)).isFalse();
    }

    @Test
    void seRedondeaHaciaAdentroDeLaBanda() {
        // 90 % de 5.005 = 4.504,5 → 4.505; 110 % = 5.505,5 → 5.505
        assertThat(BandaPrecio.minimo(5_005)).isEqualTo(4_505);
        assertThat(BandaPrecio.maximo(5_005)).isEqualTo(5_505);
        // 90 % de 7 = 6,3 → 7; 110 % = 7,7 → 7: con precios tan chicos la banda es solo la referencia
        assertThat(BandaPrecio.minimo(7)).isEqualTo(7);
        assertThat(BandaPrecio.maximo(7)).isEqualTo(7);
    }

    @Test
    void elMaximoNuncaSuperaLoQueReservaUnaCompraAMercado() {
        // portfolio-service reserva ceil(cantidad × referencia × 1,10): ejecutar todo al máximo siempre cabe
        for (long referencia = 1; referencia <= 20_000; referencia += 7) {
            for (long cantidad : new long[] {1, 3, 7, 10, 999}) {
                long reservado = (cantidad * referencia * 11 + 9) / 10;
                assertThat(cantidad * BandaPrecio.maximo(referencia)).isLessThanOrEqualTo(reservado);
            }
        }
    }

    @Test
    void unaReferenciaQueNoEsPositivaNoTieneBanda() {
        assertThatThrownBy(() -> BandaPrecio.maximo(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BandaPrecio.minimo(-5_000)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unaReferenciaEnormeFallaEnVezDeDesbordarse() {
        assertThatThrownBy(() -> BandaPrecio.maximo(Long.MAX_VALUE / 2)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> BandaPrecio.minimo(Long.MAX_VALUE / 2)).isInstanceOf(ArithmeticException.class);
    }
}
