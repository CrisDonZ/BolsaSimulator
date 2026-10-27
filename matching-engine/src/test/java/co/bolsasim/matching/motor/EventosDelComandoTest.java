package co.bolsasim.matching.motor;

import static org.assertj.core.api.Assertions.assertThat;

import co.bolsasim.eventos.AccionComando;
import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.EstadoOrden;
import co.bolsasim.eventos.EventoOrden;
import co.bolsasim.eventos.Lado;
import co.bolsasim.eventos.OperacionEjecutada;
import co.bolsasim.eventos.TipoOrden;
import co.bolsasim.eventos.Topicos;
import co.bolsasim.matching.libro.LibroOrdenes;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Prueba unitaria de los mensajes que salen de cada comando: Java puro, sin Kafka. Usa el libro real para
 * armar cada escenario.
 */
class EventosDelComandoTest {

    private static final Instant AHORA = Instant.parse("2026-10-20T15:00:00Z");

    private final LibroOrdenes libro = new LibroOrdenes("ANDX");

    @Test
    void unaOrdenQueEntraAlLibroSinCruzarQuedaAbierta() {
        ComandoOrden compra = compra("ana", 5_000L, 100);

        List<Mensaje> mensajes = procesar(compra);

        assertThat(mensajes).singleElement().satisfies(mensaje -> {
            assertThat(mensaje.topico()).isEqualTo(Topicos.ORDERS_EVENTS);
            assertThat(mensaje.clave()).isEqualTo("ANDX");
            EventoOrden evento = (EventoOrden) mensaje.evento();
            assertThat(evento.ordenId()).isEqualTo(compra.ordenId());
            assertThat(evento.estado()).isEqualTo(EstadoOrden.ABIERTA);
            assertThat(evento.cantidadEjecutada()).isZero();
            assertThat(evento.ejecucion()).isNull();
        });
    }

    @Test
    void unaEjecucionPublicaLaOperacionYUnEventoPorCadaOrden() {
        ComandoOrden venta = venta("beto", 4_900L, 100);
        procesar(venta);
        ComandoOrden compra = compra("ana", 5_000L, 100);

        List<Mensaje> mensajes = procesar(compra);

        assertThat(mensajes).extracting(Mensaje::topico)
                .containsExactly(Topicos.TRADES_EXECUTED, Topicos.ORDERS_EVENTS, Topicos.ORDERS_EVENTS);
        OperacionEjecutada operacion = (OperacionEjecutada) mensajes.get(0).evento();
        assertThat(operacion.precio()).isEqualTo(4_900L);
        assertThat(operacion.cantidad()).isEqualTo(100);
        assertThat(operacion.ordenCompraId()).isEqualTo(compra.ordenId());
        assertThat(operacion.ordenVentaId()).isEqualTo(venta.ordenId());

        // Primero la orden que esperaba en el libro, después la del comando; las dos con la misma operación
        EventoOrden deLaVenta = (EventoOrden) mensajes.get(1).evento();
        EventoOrden deLaCompra = (EventoOrden) mensajes.get(2).evento();
        assertThat(deLaVenta.ordenId()).isEqualTo(venta.ordenId());
        assertThat(deLaCompra.ordenId()).isEqualTo(compra.ordenId());
        for (EventoOrden evento : List.of(deLaVenta, deLaCompra)) {
            assertThat(evento.estado()).isEqualTo(EstadoOrden.EJECUTADA);
            assertThat(evento.cantidadEjecutada()).isEqualTo(100);
            assertThat(evento.ejecucion().operacionId()).isEqualTo(operacion.operacionId());
            assertThat(evento.ejecucion().precio()).isEqualTo(4_900L);
        }
    }

    @Test
    void laOrdenDelLibroEjecutadaAMediasQuedaParcial() {
        ComandoOrden venta = venta("beto", 5_000L, 100);
        procesar(venta);

        List<Mensaje> mensajes = procesar(compra("ana", 5_000L, 40));

        EventoOrden deLaVenta = (EventoOrden) mensajes.get(1).evento();
        assertThat(deLaVenta.estado()).isEqualTo(EstadoOrden.PARCIAL);
        assertThat(deLaVenta.cantidad()).isEqualTo(100);
        assertThat(deLaVenta.cantidadEjecutada()).isEqualTo(40);
        assertThat(((EventoOrden) mensajes.get(2).evento()).estado()).isEqualTo(EstadoOrden.EJECUTADA);
    }

    @Test
    void unaOrdenQueCruzaAMediasYQuedaEnElLibroNoNecesitaOtroEvento() {
        procesar(venta("beto", 5_000L, 30));

        List<Mensaje> mensajes = procesar(compra("ana", 5_000L, 100));

        assertThat(mensajes).hasSize(3);
        EventoOrden deLaCompra = (EventoOrden) mensajes.getLast().evento();
        assertThat(deLaCompra.estado()).isEqualTo(EstadoOrden.PARCIAL);
        assertThat(deLaCompra.cantidadEjecutada()).isEqualTo(30);
    }

    @Test
    void aUnaOrdenAMercadoQueNoAlcanzaSeLeCancelaElSobranteConElMotivo() {
        procesar(venta("beto", 5_000L, 60));
        ComandoOrden aMercado = aMercado("ana", Lado.COMPRA, 100);

        List<Mensaje> mensajes = procesar(aMercado);

        assertThat(mensajes).hasSize(4);
        EventoOrden ultimo = (EventoOrden) mensajes.getLast().evento();
        assertThat(ultimo.ordenId()).isEqualTo(aMercado.ordenId());
        assertThat(ultimo.estado()).isEqualTo(EstadoOrden.CANCELADA);
        assertThat(ultimo.cantidadEjecutada()).isEqualTo(60);
        assertThat(ultimo.ejecucion()).isNull();
        assertThat(ultimo.motivo())
                .isEqualTo("Se ejecutaron 60 de 100 acciones; el resto se canceló porque no había más órdenes de venta.");
    }

    @Test
    void unaOrdenAMercadoSinContraparteSeCancelaSinEjecutarNada() {
        List<Mensaje> mensajes = procesar(aMercado("ana", Lado.VENTA, 10));

        assertThat(mensajes).singleElement().satisfies(mensaje -> {
            EventoOrden evento = (EventoOrden) mensaje.evento();
            assertThat(evento.estado()).isEqualTo(EstadoOrden.CANCELADA);
            assertThat(evento.cantidadEjecutada()).isZero();
            assertThat(evento.motivo()).isEqualTo("No había órdenes de compra para ejecutarla.");
        });
    }

    @Test
    void loQueQuedaFueraDeLaBandaSeCancelaConLaReferenciaEnElMotivo() {
        procesar(venta("beto", 5_000L, 40));
        procesar(venta("cara", 5_600L, 100));
        ComandoOrden aMercado = aMercado("ana", Lado.COMPRA, 100);

        List<Mensaje> mensajes = procesar(aMercado);

        EventoOrden ultimo = (EventoOrden) mensajes.getLast().evento();
        assertThat(ultimo.ordenId()).isEqualTo(aMercado.ordenId());
        assertThat(ultimo.estado()).isEqualTo(EstadoOrden.CANCELADA);
        assertThat(ultimo.cantidadEjecutada()).isEqualTo(40);
        assertThat(ultimo.motivo()).isEqualTo("Se ejecutaron 40 de 100 acciones; sin más contraparte dentro de "
                + "±10 % de 5.000 COP, se canceló lo no ejecutado.");
        // El motivo no dice nada de la otra orden: ni su usuario ni su precio
        assertThat(ultimo.motivo()).doesNotContain("cara", "5.600");
    }

    @Test
    void sinNadaDentroDeLaBandaSeCancelaCompletaConSuMotivo() {
        procesar(venta("beto", 5_600L, 100));

        List<Mensaje> mensajes = procesar(aMercado("ana", Lado.COMPRA, 10));

        assertThat(mensajes).singleElement().satisfies(mensaje -> {
            EventoOrden evento = (EventoOrden) mensaje.evento();
            assertThat(evento.estado()).isEqualTo(EstadoOrden.CANCELADA);
            assertThat(evento.cantidadEjecutada()).isZero();
            assertThat(evento.motivo())
                    .isEqualTo("Sin contraparte dentro de ±10 % de 5.000 COP: se canceló la orden sin ejecutar nada.");
        });
    }

    @Test
    void unaOrdenAMercadoSinReferenciaSeCancelaCompletaYNoSaleNingunaOperacion() {
        procesar(venta("beto", 5_000L, 100));

        List<Mensaje> mensajes = procesar(aMercado("ana", Lado.COMPRA, null, 10));

        assertThat(mensajes).singleElement().satisfies(mensaje -> {
            assertThat(mensaje.topico()).isEqualTo(Topicos.ORDERS_EVENTS);
            EventoOrden evento = (EventoOrden) mensaje.evento();
            assertThat(evento.estado()).isEqualTo(EstadoOrden.CANCELADA);
            assertThat(evento.cantidadEjecutada()).isZero();
            assertThat(evento.motivo()).isEqualTo(EventosDelComando.SIN_PRECIO_DE_REFERENCIA);
        });
    }

    @Test
    void cancelarUnaOrdenDelLibroPublicaCanceladaConLoQueSeEjecuto() {
        ComandoOrden venta = venta("beto", 5_000L, 100);
        procesar(venta);
        procesar(compra("ana", 5_000L, 30));

        List<Mensaje> mensajes = procesar(cancelar(venta));

        assertThat(mensajes).singleElement().satisfies(mensaje -> {
            EventoOrden evento = (EventoOrden) mensaje.evento();
            assertThat(evento.ordenId()).isEqualTo(venta.ordenId());
            assertThat(evento.usuarioId()).isEqualTo("beto");
            assertThat(evento.estado()).isEqualTo(EstadoOrden.CANCELADA);
            assertThat(evento.cantidadEjecutada()).isEqualTo(30);
            assertThat(evento.motivo()).isEqualTo("Cancelada por el usuario.");
        });
    }

    @Test
    void cancelarUnaOrdenQueYaNoEstaNoPublicaNada() {
        assertThat(procesar(cancelar(venta("beto", 5_000L, 100)))).isEmpty();
    }

    @Test
    void losIdsSonLosMismosSiElComandoSeVuelveAProcesarYDistintosEntreMensajes() {
        procesar(venta("beto", 5_000L, 100));
        ComandoOrden compra = compra("ana", 5_000L, 100);
        var resultado = libro.procesar(compra);

        List<Mensaje> primeraVez = EventosDelComando.de(compra, resultado, AHORA);
        List<Mensaje> otraVez = EventosDelComando.de(compra, resultado, AHORA.plusSeconds(5));

        assertThat(ids(otraVez)).isEqualTo(ids(primeraVez)).doesNotHaveDuplicates();
    }

    private List<Mensaje> procesar(ComandoOrden comando) {
        return EventosDelComando.de(comando, libro.procesar(comando), AHORA);
    }

    private static List<UUID> ids(List<Mensaje> mensajes) {
        return mensajes.stream()
                .map(mensaje -> mensaje.evento() instanceof EventoOrden evento
                        ? evento.eventoId()
                        : ((OperacionEjecutada) mensaje.evento()).operacionId())
                .toList();
    }

    private static ComandoOrden compra(String usuario, long precio, int cantidad) {
        return ComandoOrden.nueva(usuario, "ANDX", Lado.COMPRA, TipoOrden.LIMITE, precio, cantidad);
    }

    private static ComandoOrden venta(String usuario, long precio, int cantidad) {
        return ComandoOrden.nueva(usuario, "ANDX", Lado.VENTA, TipoOrden.LIMITE, precio, cantidad);
    }

    /** Con referencia 5.000: su banda va de 4.500 a 5.500. */
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
