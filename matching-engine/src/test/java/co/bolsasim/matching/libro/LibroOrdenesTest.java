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

        List<Ejecucion> ejecuciones = libro.procesar(compra);

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

        List<Ejecucion> ejecuciones = libro.procesar(compra);

        assertThat(ejecuciones).containsExactly(
                new Ejecucion("ANDX", 5_000, 100, compra.ordenId(), "ana", venta.ordenId(), "beto"));
        assertThat(libro.mejorCompra()).isEmpty();
        assertThat(libro.mejorVenta()).isEmpty();
    }

    @Test
    void elPrecioLoPoneLaOrdenQueEstabaEsperando() {
        libro.procesar(ventaLimite("beto", 4_900, 100));

        // Ana estaba dispuesta a pagar 5000, pero alguien ya vendía a 4900
        List<Ejecucion> ejecuciones = libro.procesar(compraLimite("ana", 5_000, 100));

        assertThat(ejecuciones).singleElement().extracting(Ejecucion::precio).isEqualTo(4_900L);
    }

    @Test
    void siNoAlcanzaLaContraparteLoQueSobraQuedaEnElLibro() {
        libro.procesar(ventaLimite("beto", 5_000, 30));
        ComandoOrden compra = compraLimite("ana", 5_000, 100);

        List<Ejecucion> ejecuciones = libro.procesar(compra);

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

        List<Ejecucion> ejecuciones = libro.procesar(compraLimite("ana", 5_100, 50));

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

        List<Ejecucion> ejecuciones = libro.procesar(compraLimite("ana", 5_000, 50));

        assertThat(ejecuciones).singleElement()
                .extracting(Ejecucion::ordenVentaId).isEqualTo(primera.ordenId());
        assertThat(libro.pendienteDe(segunda.ordenId())).isEqualTo(50);
    }

    @Test
    void unaOrdenGrandeRecorreVariosPrecios() {
        libro.procesar(ventaLimite("beto", 5_000, 30));
        libro.procesar(ventaLimite("cara", 5_100, 30));
        libro.procesar(ventaLimite("dani", 5_200, 30));

        List<Ejecucion> ejecuciones = libro.procesar(compraLimite("ana", 5_100, 100));

        // Llega hasta 5100, su límite; la venta de 5200 queda sin tocar
        assertThat(ejecuciones).extracting(Ejecucion::precio).containsExactly(5_000L, 5_100L);
        assertThat(libro.mejorCompra()).contains(5_100L);
        assertThat(libro.mejorVenta()).contains(5_200L);
    }

    @Test
    void compraPorDebajoDeLaMejorVentaNoSeEjecuta() {
        libro.procesar(ventaLimite("beto", 5_100, 100));

        List<Ejecucion> ejecuciones = libro.procesar(compraLimite("ana", 5_000, 100));

        assertThat(ejecuciones).isEmpty();
        assertThat(libro.mejorCompra()).contains(5_000L);
        assertThat(libro.mejorVenta()).contains(5_100L);
    }

    @Test
    void ventaAMercadoSeEjecutaContraLasMejoresCompras() {
        libro.procesar(compraLimite("ana", 4_900, 50));
        libro.procesar(compraLimite("beto", 5_000, 50));

        List<Ejecucion> ejecuciones = libro.procesar(aMercado("cara", Lado.VENTA, 80));

        assertThat(ejecuciones).extracting(Ejecucion::precio, Ejecucion::cantidad)
                .containsExactly(
                        tuple(5_000L, 50),
                        tuple(4_900L, 30));
    }

    @Test
    void loQueSobraDeUnaOrdenAMercadoSeDescarta() {
        libro.procesar(ventaLimite("beto", 5_000, 30));
        ComandoOrden compra = aMercado("ana", Lado.COMPRA, 100);

        List<Ejecucion> ejecuciones = libro.procesar(compra);

        assertThat(ejecuciones).singleElement().extracting(Ejecucion::cantidad).isEqualTo(30);
        assertThat(libro.pendienteDe(compra.ordenId())).isZero();
        assertThat(libro.mejorCompra()).isEmpty();
    }

    @Test
    void cancelarRetiraLaOrdenDelLibro() {
        ComandoOrden venta = ventaLimite("beto", 5_000, 100);
        libro.procesar(venta);

        List<Ejecucion> ejecuciones = libro.procesar(cancelar(venta));

        assertThat(ejecuciones).isEmpty();
        assertThat(libro.mejorVenta()).isEmpty();
        assertThat(libro.procesar(compraLimite("ana", 5_000, 100))).isEmpty();
    }

    @Test
    void cancelarUnaOrdenQueYaNoExisteNoHaceNada() {
        ComandoOrden venta = ventaLimite("beto", 5_000, 100);
        libro.procesar(venta);
        libro.procesar(compraLimite("ana", 5_000, 100));

        assertThat(libro.procesar(cancelar(venta))).isEmpty();
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

    private static ComandoOrden aMercado(String usuario, Lado lado, int cantidad) {
        return ComandoOrden.nueva(usuario, "ANDX", lado, TipoOrden.MERCADO, null, cantidad);
    }

    private static ComandoOrden cancelar(ComandoOrden orden) {
        return new ComandoOrden(UUID.randomUUID(), AccionComando.CANCELAR, orden.ordenId(), orden.usuarioId(),
                orden.simbolo(), orden.lado(), orden.tipo(), orden.precioLimite(), orden.cantidad(), Instant.now());
    }
}
