package co.bolsasim.matching.libro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import co.bolsasim.eventos.AccionComando;
import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.Lado;
import co.bolsasim.eventos.TipoOrden;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Prueba unitaria del libro: Java puro, sin Spring ni Kafka.
 * Cada prueba arma un escenario de órdenes y revisa qué ejecuciones salen y qué queda en el libro.
 */
class LibroOrdenesTest {

    private final LibroOrdenes libro = new LibroOrdenes("ANDX");

    @Test
    void ordenSinContraparteQuedaEsperandoEnElLibro() {
        ComandoOrden compra = compraLimite("ana", 5_000, 100);

        List<Ejecucion> ejecuciones = libro.procesar(compra).ejecuciones();

        assertThat(ejecuciones).isEmpty();
        assertThat(libro.mejorCompra()).contains(5_000L);
        assertThat(libro.mejorVenta()).isEmpty();
        assertThat(libro.pendienteDe(compra.ordenId())).isEqualTo(100);
    }

    @Test
    void compraYVentaAlMismoPrecioSeEjecutanCompletas() {
        ComandoOrden venta = ventaLimite("beto", 5_000, 100);
        ComandoOrden compra = compraLimite("ana", 5_000, 100);
        libro.procesar(venta);

        List<Ejecucion> ejecuciones = libro.procesar(compra).ejecuciones();

        assertThat(ejecuciones).containsExactly(
                new Ejecucion("ANDX", 5_000, 100, compra.ordenId(), "ana", 100, 100, venta.ordenId(), "beto", 100, 100));
        assertThat(libro.mejorCompra()).isEmpty();
        assertThat(libro.mejorVenta()).isEmpty();
    }

    @Test
    void elPrecioLoPoneLaOrdenQueEstabaEsperando() {
        libro.procesar(ventaLimite("beto", 4_900, 100));

        // Ana estaba dispuesta a pagar 5000, pero alguien ya vendía a 4900
        List<Ejecucion> ejecuciones = libro.procesar(compraLimite("ana", 5_000, 100)).ejecuciones();

        assertThat(ejecuciones).singleElement().extracting(Ejecucion::precio).isEqualTo(4_900L);
    }

    @Test
    void siNoAlcanzaLaContraparteLoQueSobraQuedaEnElLibro() {
        libro.procesar(ventaLimite("beto", 5_000, 30));
        ComandoOrden compra = compraLimite("ana", 5_000, 100);

        List<Ejecucion> ejecuciones = libro.procesar(compra).ejecuciones();

        assertThat(ejecuciones).singleElement().extracting(Ejecucion::cantidad).isEqualTo(30);
        assertThat(libro.pendienteDe(compra.ordenId())).isEqualTo(70);
        assertThat(libro.mejorCompra()).contains(5_000L);
        assertThat(libro.mejorVenta()).isEmpty();
    }

    @Test
    void laOrdenQueEsperaTambienSePuedeEjecutarPorPartes() {
        ComandoOrden venta = ventaLimite("beto", 5_000, 100);
        libro.procesar(venta);

        libro.procesar(compraLimite("ana", 5_000, 40));

        assertThat(libro.pendienteDe(venta.ordenId())).isEqualTo(60);
        assertThat(libro.mejorVenta()).contains(5_000L);
    }

    @Test
    void primeroSeAtiendeElMejorPrecio() {
        libro.procesar(ventaLimite("cara", 5_100, 50));
        ComandoOrden ventaBarata = ventaLimite("beto", 5_000, 50);
        libro.procesar(ventaBarata);

        List<Ejecucion> ejecuciones = libro.procesar(compraLimite("ana", 5_100, 50)).ejecuciones();

        // La venta de 5000 llegó después, pero es más barata: va primero
        assertThat(ejecuciones).singleElement().satisfies(e -> {
            assertThat(e.ordenVentaId()).isEqualTo(ventaBarata.ordenId());
            assertThat(e.precio()).isEqualTo(5_000L);
        });
        assertThat(libro.mejorVenta()).contains(5_100L);
    }

    @Test
    void aIgualPrecioSeAtiendePrimeroLaQueLlegoAntes() {
        ComandoOrden primera = ventaLimite("beto", 5_000, 50);
        ComandoOrden segunda = ventaLimite("cara", 5_000, 50);
        libro.procesar(primera);
        libro.procesar(segunda);

        List<Ejecucion> ejecuciones = libro.procesar(compraLimite("ana", 5_000, 50)).ejecuciones();

        assertThat(ejecuciones).singleElement()
                .extracting(Ejecucion::ordenVentaId).isEqualTo(primera.ordenId());
        assertThat(libro.pendienteDe(segunda.ordenId())).isEqualTo(50);
    }

    @Test
    void unaOrdenGrandeRecorreVariosPrecios() {
        libro.procesar(ventaLimite("beto", 5_000, 30));
        libro.procesar(ventaLimite("cara", 5_100, 30));
        libro.procesar(ventaLimite("dani", 5_200, 30));

        List<Ejecucion> ejecuciones = libro.procesar(compraLimite("ana", 5_100, 100)).ejecuciones();

        // Llega hasta 5100, su límite; la venta de 5200 queda sin tocar
        assertThat(ejecuciones).extracting(Ejecucion::precio).containsExactly(5_000L, 5_100L);
        assertThat(libro.mejorCompra()).contains(5_100L);
        assertThat(libro.mejorVenta()).contains(5_200L);
    }

    @Test
    void compraPorDebajoDeLaMejorVentaNoSeEjecuta() {
        libro.procesar(ventaLimite("beto", 5_100, 100));

        List<Ejecucion> ejecuciones = libro.procesar(compraLimite("ana", 5_000, 100)).ejecuciones();

        assertThat(ejecuciones).isEmpty();
        assertThat(libro.mejorCompra()).contains(5_000L);
        assertThat(libro.mejorVenta()).contains(5_100L);
    }

    @Test
    void ventaAMercadoSeEjecutaContraLasMejoresCompras() {
        libro.procesar(compraLimite("ana", 4_900, 50));
        libro.procesar(compraLimite("beto", 5_000, 50));

        List<Ejecucion> ejecuciones = libro.procesar(aMercado("cara", Lado.VENTA, 80)).ejecuciones();

        assertThat(ejecuciones).extracting(Ejecucion::precio, Ejecucion::cantidad)
                .containsExactly(
                        tuple(5_000L, 50),
                        tuple(4_900L, 30));
    }

    @Test
    void loQueSobraDeUnaOrdenAMercadoSeDescarta() {
        libro.procesar(ventaLimite("beto", 5_000, 30));
        ComandoOrden compra = aMercado("ana", Lado.COMPRA, 100);

        List<Ejecucion> ejecuciones = libro.procesar(compra).ejecuciones();

        assertThat(ejecuciones).singleElement().extracting(Ejecucion::cantidad).isEqualTo(30);
        assertThat(libro.pendienteDe(compra.ordenId())).isZero();
        assertThat(libro.mejorCompra()).isEmpty();
    }

    @Test
    void unaOrdenSinContraparteTerminaEnElLibro() {
        assertThat(libro.procesar(compraLimite("ana", 5_000, 100)).desenlace())
                .isEqualTo(ResultadoComando.Desenlace.EN_LIBRO);
    }

    @Test
    void cadaEjecucionLlevaLoAcumuladoDeLasDosOrdenes() {
        ComandoOrden venta = ventaLimite("beto", 5_000, 100);
        libro.procesar(venta);

        ResultadoComando primera = libro.procesar(compraLimite("ana", 5_000, 40));
        ResultadoComando segunda = libro.procesar(compraLimite("cris", 5_000, 60));

        // La venta de Beto se ejecuta en dos partes: 40 y luego 60, de 100
        assertThat(primera.ejecuciones()).singleElement().satisfies(e -> {
            assertThat(e.cantidadVenta()).isEqualTo(100);
            assertThat(e.ejecutadaVenta()).isEqualTo(40);
            assertThat(e.cantidadCompra()).isEqualTo(40);
            assertThat(e.ejecutadaCompra()).isEqualTo(40);
        });
        assertThat(segunda.ejecuciones()).singleElement().extracting(Ejecucion::ejecutadaVenta).isEqualTo(100);
        assertThat(primera.desenlace()).isEqualTo(ResultadoComando.Desenlace.EJECUTADA);
    }

    @Test
    void unaOrdenQueCruzaAMediasQuedaEnElLibroConLoEjecutado() {
        libro.procesar(ventaLimite("beto", 5_000, 30));
        libro.procesar(ventaLimite("cara", 5_000, 20));

        ResultadoComando resultado = libro.procesar(compraLimite("ana", 5_000, 100));

        assertThat(resultado.desenlace()).isEqualTo(ResultadoComando.Desenlace.EN_LIBRO);
        assertThat(resultado.ejecuciones()).extracting(Ejecucion::ejecutadaCompra).containsExactly(30, 50);
    }

    @Test
    void aUnaOrdenAMercadoQueNoAlcanzaSeLeCancelaElSobrante() {
        libro.procesar(ventaLimite("beto", 5_000, 30));

        ResultadoComando resultado = libro.procesar(aMercado("ana", Lado.COMPRA, 100));

        assertThat(resultado.desenlace()).isEqualTo(ResultadoComando.Desenlace.SOBRANTE_CANCELADO);
        assertThat(resultado.ejecuciones()).singleElement().extracting(Ejecucion::ejecutadaCompra).isEqualTo(30);
    }

    @Test
    void unaOrdenAMercadoSinContraparteSeCancelaCompleta() {
        ResultadoComando resultado = libro.procesar(aMercado("ana", Lado.VENTA, 10));

        assertThat(resultado.desenlace()).isEqualTo(ResultadoComando.Desenlace.SOBRANTE_CANCELADO);
        assertThat(resultado.ejecuciones()).isEmpty();
    }

    @Test
    void unaCompraAMercadoLlegaHastaElMaximoDeLaBandaYNoMasAlla() {
        libro.procesar(ventaLimite("beto", 5_000, 30));
        libro.procesar(ventaLimite("cara", 5_500, 30));
        ComandoOrden fuera = ventaLimite("dani", 5_510, 30);
        libro.procesar(fuera);

        ResultadoComando resultado = libro.procesar(aMercado("ana", Lado.COMPRA, 100));

        // Referencia 5.000: 5.500 es el máximo y cuenta; 5.510 queda fuera y se cancela el resto
        assertThat(resultado.ejecuciones()).extracting(Ejecucion::precio, Ejecucion::cantidad)
                .containsExactly(tuple(5_000L, 30), tuple(5_500L, 30));
        assertThat(resultado.desenlace()).isEqualTo(ResultadoComando.Desenlace.FUERA_DE_BANDA);
        // La venta fuera de la banda sigue en el libro, sin tocar, y la compra no quedó en él
        assertThat(libro.pendienteDe(fuera.ordenId())).isEqualTo(30);
        assertThat(libro.mejorVenta()).contains(5_510L);
        assertThat(libro.mejorCompra()).isEmpty();
    }

    @Test
    void unaVentaAMercadoLlegaHastaElMinimoDeLaBandaYNoMasAlla() {
        libro.procesar(compraLimite("beto", 4_500, 40));
        ComandoOrden fuera = compraLimite("cara", 4_490, 40);
        libro.procesar(fuera);

        ResultadoComando resultado = libro.procesar(aMercado("ana", Lado.VENTA, 100));

        assertThat(resultado.ejecuciones()).singleElement().satisfies(e -> {
            assertThat(e.precio()).isEqualTo(4_500L);
            assertThat(e.cantidad()).isEqualTo(40);
        });
        assertThat(resultado.desenlace()).isEqualTo(ResultadoComando.Desenlace.FUERA_DE_BANDA);
        assertThat(libro.pendienteDe(fuera.ordenId())).isEqualTo(40);
        assertThat(libro.mejorVenta()).isEmpty();
    }

    @Test
    void sinContraparteDentroDeLaBandaUnaOrdenAMercadoSeCancelaSinEjecutarNada() {
        libro.procesar(ventaLimite("beto", 5_600, 100));
        libro.procesar(ventaLimite("cara", 6_000, 100));

        ResultadoComando resultado = libro.procesar(aMercado("ana", Lado.COMPRA, 10));

        assertThat(resultado.ejecuciones()).isEmpty();
        assertThat(resultado.desenlace()).isEqualTo(ResultadoComando.Desenlace.FUERA_DE_BANDA);
        assertThat(libro.mejorVenta()).contains(5_600L);
    }

    @Test
    void laBandaSeRedondeaHaciaAdentro() {
        // Referencia 5.005: el máximo es floor(5.505,5) = 5.505
        libro.procesar(ventaLimite("beto", 5_505, 10));
        libro.procesar(ventaLimite("cara", 5_506, 10));

        ResultadoComando resultado = libro.procesar(aMercado("ana", Lado.COMPRA, 5_005L, 20));

        assertThat(resultado.ejecuciones()).singleElement().extracting(Ejecucion::precio).isEqualTo(5_505L);
        assertThat(resultado.desenlace()).isEqualTo(ResultadoComando.Desenlace.FUERA_DE_BANDA);
    }

    @Test
    void unaOrdenAMercadoSinReferenciaSeCancelaSinTocarElLibro() {
        ComandoOrden venta = ventaLimite("beto", 5_000, 100);
        libro.procesar(venta);

        ResultadoComando resultado = libro.procesar(aMercado("ana", Lado.COMPRA, null, 10));

        assertThat(resultado.desenlace()).isEqualTo(ResultadoComando.Desenlace.SIN_REFERENCIA);
        assertThat(resultado.ejecuciones()).isEmpty();
        assertThat(libro.pendienteDe(venta.ordenId())).isEqualTo(100);
    }

    @Test
    void unaReferenciaInvalidaTambienCancelaLaOrdenEnVezDeTrabarElMotor() {
        libro.procesar(ventaLimite("beto", 5_000, 100));

        assertThat(libro.procesar(aMercado("ana", Lado.COMPRA, 0L, 10)).desenlace())
                .isEqualTo(ResultadoComando.Desenlace.SIN_REFERENCIA);
        // Tan grande que su banda no cabe en un long
        assertThat(libro.procesar(aMercado("ana", Lado.COMPRA, Long.MAX_VALUE / 2, 10)).desenlace())
                .isEqualTo(ResultadoComando.Desenlace.SIN_REFERENCIA);
        assertThat(libro.mejorVenta()).contains(5_000L);
    }

    @Test
    void unaOrdenLimiteNoUsaLaReferencia() {
        libro.procesar(ventaLimite("beto", 6_000, 10));

        // Una LIMITE de 6.000 con referencia 5.000 cruza: su límite lo validó order-service al crearla
        ComandoOrden compra = ComandoOrden.nueva("ana", "ANDX", Lado.COMPRA, TipoOrden.LIMITE, 6_000L, 5_000L, 10);

        assertThat(libro.procesar(compra).desenlace()).isEqualTo(ResultadoComando.Desenlace.EJECUTADA);
    }

    @Test
    void cancelarDiceCuantoSeAlcanzoAEjecutar() {
        ComandoOrden venta = ventaLimite("beto", 5_000, 100);
        libro.procesar(venta);
        libro.procesar(compraLimite("ana", 5_000, 30));

        ResultadoComando resultado = libro.procesar(cancelar(venta));

        assertThat(resultado.desenlace()).isEqualTo(ResultadoComando.Desenlace.CANCELADA);
        assertThat(resultado.cancelada())
                .isEqualTo(new ResultadoComando.OrdenCancelada(venta.ordenId(), "beto", 100, 30));
    }

    @Test
    void cancelarUnaOrdenQueNoEstaNoCambiaNada() {
        assertThat(libro.procesar(cancelar(ventaLimite("beto", 5_000, 100))).desenlace())
                .isEqualTo(ResultadoComando.Desenlace.SIN_CAMBIOS);
    }

    @Test
    void cancelarRetiraLaOrdenDelLibro() {
        ComandoOrden venta = ventaLimite("beto", 5_000, 100);
        libro.procesar(venta);

        List<Ejecucion> ejecuciones = libro.procesar(cancelar(venta)).ejecuciones();

        assertThat(ejecuciones).isEmpty();
        assertThat(libro.mejorVenta()).isEmpty();
        assertThat(libro.procesar(compraLimite("ana", 5_000, 100)).ejecuciones()).isEmpty();
    }

    @Test
    void cancelarUnaOrdenQueYaNoExisteNoHaceNada() {
        ComandoOrden venta = ventaLimite("beto", 5_000, 100);
        libro.procesar(venta);
        libro.procesar(compraLimite("ana", 5_000, 100));

        assertThat(libro.procesar(cancelar(venta)).ejecuciones()).isEmpty();
    }

    @Test
    void rechazaOrdenesDeOtroSimbolo() {
        ComandoOrden deCafe = ComandoOrden.nueva("ana", "CAFE", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 10);

        assertThatThrownBy(() -> libro.procesar(deCafe))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CAFE");
    }

    private static ComandoOrden compraLimite(String usuario, long precio, int cantidad) {
        return ComandoOrden.nueva(usuario, "ANDX", Lado.COMPRA, TipoOrden.LIMITE, precio, cantidad);
    }

    private static ComandoOrden ventaLimite(String usuario, long precio, int cantidad) {
        return ComandoOrden.nueva(usuario, "ANDX", Lado.VENTA, TipoOrden.LIMITE, precio, cantidad);
    }

    /** Una orden a mercado con referencia 5.000: su banda va de 4.500 a 5.500. */
    private static ComandoOrden aMercado(String usuario, Lado lado, int cantidad) {
        return aMercado(usuario, lado, 5_000L, cantidad);
    }

    private static ComandoOrden aMercado(String usuario, Lado lado, Long referencia, int cantidad) {
        return ComandoOrden.nueva(usuario, "ANDX", lado, TipoOrden.MERCADO, null, referencia, cantidad);
    }

    private static ComandoOrden cancelar(ComandoOrden orden) {
        return new ComandoOrden(UUID.randomUUID(), AccionComando.CANCELAR, orden.ordenId(), orden.usuarioId(),
                orden.simbolo(), orden.lado(), orden.tipo(), orden.precioLimite(), orden.precioReferencia(), orden.cantidad(),
                Instant.now());
    }
}
