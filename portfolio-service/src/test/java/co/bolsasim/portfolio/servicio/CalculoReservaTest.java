package co.bolsasim.portfolio.servicio;

import static org.assertj.core.api.Assertions.assertThat;

import co.bolsasim.eventos.Lado;
import co.bolsasim.eventos.SolicitudReserva;
import co.bolsasim.eventos.TipoOrden;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Prueba unitaria de cuánto reserva cada compra y del motivo de una venta rechazada (RF-17, RF-18): Java puro. */
class CalculoReservaTest {

    @Test
    void unaCompraLimiteReservaPrecioPorCantidad() {
        // HU-05: 100 acciones a 5.000
        assertThat(ServicioReservas.montoAReservar(compra(TipoOrden.LIMITE, 5_000L, 100, 4_900))).hasValue(500_000);
    }

    @Test
    void unaCompraAMercadoReservaDiezPorCientoMasDelUltimoPrecio() {
        // HU-07: 10 acciones con último precio 5.000 reservan 55.000
        assertThat(ServicioReservas.montoAReservar(compra(TipoOrden.MERCADO, null, 10, 5_000))).hasValue(55_000);
    }

    @Test
    void elDiezPorCientoSeRedondeaHaciaArribaParaNoQuedarseCorto() {
        // 3 × 3.505 × 1,10 = 11.566,5
        assertThat(ServicioReservas.montoAReservar(compra(TipoOrden.MERCADO, null, 3, 3_505))).hasValue(11_567);
        // 7 × 1.500 × 1,10 = 11.550 exacto: no se redondea
        assertThat(ServicioReservas.montoAReservar(compra(TipoOrden.MERCADO, null, 7, 1_500))).hasValue(11_550);
    }

    @Test
    void unMontoQueNoCabeEnUnLongNoSeReserva() {
        assertThat(ServicioReservas.montoAReservar(compra(TipoOrden.LIMITE, Long.MAX_VALUE / 2, 3, 5_000))).isEmpty();
        assertThat(ServicioReservas.montoAReservar(compra(TipoOrden.MERCADO, null, Integer.MAX_VALUE,
                Long.MAX_VALUE / 100))).isEmpty();
    }

    @Test
    void elMotivoDeUnaVentaSinAccionesDiceCuantasTieneYCuantasVende() {
        assertThat(ServicioReservas.motivoSinAcciones(400, "ANDX", 500))
                .isEqualTo("Acciones insuficientes: tienes 400 de ANDX disponibles y la orden vende 500.");
        assertThat(ServicioReservas.motivoSinAcciones(0, "CAFE", 1_500))
                .isEqualTo("Acciones insuficientes: tienes 0 de CAFE disponibles y la orden vende 1.500.");
    }

    @Test
    void elMontoDelMotivoSeEscribeConPuntoDeMiles() {
        assertThat(Pesos.formato(1_000_000)).isEqualTo("1.000.000");
        assertThat(Pesos.formato(950)).isEqualTo("950");
        assertThat(Pesos.formato(0)).isEqualTo("0");
    }

    private static SolicitudReserva compra(TipoOrden tipo, Long precioLimite, int cantidad, long ultimoPrecio) {
        return new SolicitudReserva(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString(), "ANDX",
                Lado.COMPRA, tipo, precioLimite, cantidad, ultimoPrecio, Instant.now());
    }
}
