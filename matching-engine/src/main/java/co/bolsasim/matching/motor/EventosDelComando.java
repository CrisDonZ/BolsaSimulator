package co.bolsasim.matching.motor;

import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.EjecucionOrden;
import co.bolsasim.eventos.EstadoOrden;
import co.bolsasim.eventos.EventoOrden;
import co.bolsasim.eventos.Lado;
import co.bolsasim.eventos.OperacionEjecutada;
import co.bolsasim.eventos.Topicos;
import co.bolsasim.matching.libro.Ejecucion;
import co.bolsasim.matching.libro.ResultadoComando;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Convierte lo que pasó en el libro con un comando en los mensajes para orders.events y trades.executed (RF-28).
 * Java puro: se prueba sin Kafka.
 *
 * <p>Por cada ejecución sale una operación y un evento para cada una de las dos órdenes: primero la que estaba
 * en el libro, después la del comando. Al final, si hace falta, un evento más de la orden del comando: ABIERTA
 * si entró al libro sin cruzar, o CANCELADA si le sobró algo a una orden a mercado o si el comando era CANCELAR.
 *
 * <p>Los ids salen del comandoId y de la posición del mensaje dentro del comando: procesar otra vez el mismo
 * comando da los mismos ids, y quien consume los eventos reconoce los repetidos (RNF-09).
 */
public final class EventosDelComando {

    static final String CANCELADA_POR_EL_USUARIO = "Cancelada por el usuario.";

    private EventosDelComando() {
    }

    public static List<Mensaje> de(ComandoOrden comando, ResultadoComando resultado, Instant ahora) {
        var armador = new Armador(comando, ahora);
        boolean comandoCompra = comando.lado() == Lado.COMPRA;

        for (Ejecucion e : resultado.ejecuciones()) {
            UUID operacionId = armador.operacion(e);
            EjecucionOrden ejecucion = new EjecucionOrden(operacionId, e.precio(), e.cantidad());
            if (comandoCompra) {
                armador.evento(e.ordenVentaId(), e.vendedorId(), e.cantidadVenta(), e.ejecutadaVenta(), ejecucion);
                armador.evento(e.ordenCompraId(), e.compradorId(), e.cantidadCompra(), e.ejecutadaCompra(), ejecucion);
            } else {
                armador.evento(e.ordenCompraId(), e.compradorId(), e.cantidadCompra(), e.ejecutadaCompra(), ejecucion);
                armador.evento(e.ordenVentaId(), e.vendedorId(), e.cantidadVenta(), e.ejecutadaVenta(), ejecucion);
            }
        }

        int ejecutada = resultado.ejecuciones().stream().mapToInt(Ejecucion::cantidad).sum();
        switch (resultado.desenlace()) {
            // Si ya cruzó una parte, el último evento de ejecución ya dice PARCIAL
            case EN_LIBRO -> {
                if (ejecutada == 0) {
                    armador.sinEjecucion(comando.ordenId(), comando.usuarioId(), EstadoOrden.ABIERTA, comando.cantidad(), 0,
                            null);
                }
            }
            case SOBRANTE_CANCELADO -> armador.sinEjecucion(comando.ordenId(), comando.usuarioId(), EstadoOrden.CANCELADA,
                    comando.cantidad(), ejecutada, motivoDelSobrante(comando, ejecutada));
            case CANCELADA -> {
                ResultadoComando.OrdenCancelada cancelada = resultado.cancelada();
                armador.sinEjecucion(cancelada.ordenId(), cancelada.usuarioId(), EstadoOrden.CANCELADA, cancelada.cantidad(),
                        cancelada.ejecutada(), CANCELADA_POR_EL_USUARIO);
            }
            // EJECUTADA: el último evento de ejecución ya lo dice. SIN_CAMBIOS: no hay nada que contar
            case EJECUTADA, SIN_CAMBIOS -> {
            }
        }
        return armador.mensajes();
    }

    /** RF-26: lo que no se ejecuta de una orden a mercado se cancela. */
    static String motivoDelSobrante(ComandoOrden comando, int ejecutada) {
        String contraparte = comando.lado() == Lado.COMPRA ? "venta" : "compra";
        if (ejecutada == 0) {
            return "No había órdenes de " + contraparte + " para ejecutarla.";
        }
        return "Se ejecutaron " + ejecutada + " de " + comando.cantidad() + " acciones; el resto se canceló porque "
                + "no había más órdenes de " + contraparte + ".";
    }

    /** Arma los mensajes de un comando en orden, cada uno con su id determinista. */
    private static final class Armador {

        private final ComandoOrden comando;
        private final Instant ahora;
        private final List<Mensaje> mensajes = new ArrayList<>();

        Armador(ComandoOrden comando, Instant ahora) {
            this.comando = comando;
            this.ahora = ahora;
        }

        UUID operacion(Ejecucion e) {
            UUID operacionId = siguienteId("operacion");
            agregar(Topicos.TRADES_EXECUTED, new OperacionEjecutada(operacionId, e.simbolo(), e.precio(), e.cantidad(),
                    e.ordenCompraId(), e.compradorId(), e.ordenVentaId(), e.vendedorId(), ahora));
            return operacionId;
        }

        /** Evento de una ejecución: PARCIAL o EJECUTADA según lo que le quede a la orden. */
        void evento(UUID ordenId, String usuarioId, int cantidad, int ejecutada, EjecucionOrden ejecucion) {
            EstadoOrden estado = ejecutada == cantidad ? EstadoOrden.EJECUTADA : EstadoOrden.PARCIAL;
            agregar(Topicos.ORDERS_EVENTS, new EventoOrden(siguienteId("evento"), ordenId, usuarioId,
                    comando.simbolo(), estado, cantidad, ejecutada, ejecucion, null, ahora));
        }

        /** Evento sin ejecución: la orden entró al libro o se canceló. */
        void sinEjecucion(UUID ordenId, String usuarioId, EstadoOrden estado, int cantidad, int ejecutada, String motivo) {
            agregar(Topicos.ORDERS_EVENTS, new EventoOrden(siguienteId("evento"), ordenId, usuarioId,
                    comando.simbolo(), estado, cantidad, ejecutada, null, motivo, ahora));
        }

        List<Mensaje> mensajes() {
            return List.copyOf(mensajes);
        }

        private void agregar(String topico, Object evento) {
            mensajes.add(new Mensaje(topico, comando.simbolo(), evento));
        }

        /** Siempre el mismo UUID para el mismo comando y la misma posición del mensaje. */
        private UUID siguienteId(String tipo) {
            String nombre = comando.comandoId() + "/" + tipo + "/" + mensajes.size();
            return UUID.nameUUIDFromBytes(nombre.getBytes(StandardCharsets.UTF_8));
        }
    }
}
