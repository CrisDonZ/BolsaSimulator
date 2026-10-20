package co.bolsasim.eventos;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento que el motor publica en {@link Topicos#ORDERS_EVENTS} cada vez que una orden cambia: entra al libro,
 * se ejecuta una parte o toda, o se cancela (RF-28). Lo consume order-service, y después portfolio-service y
 * notification-service.
 *
 * <p>Lleva el estado completo de la orden después del cambio, así quien lo lee no tiene que reconstruir nada.
 * Si el cambio fue una ejecución, también lleva el detalle de esa ejecución.
 *
 * <p>El eventoId sale del comando que lo produjo: si el motor procesa otra vez el mismo comando, el evento
 * tiene el mismo id, y quien lo consume lo reconoce como repetido (RNF-09).
 *
 * @param eventoId          identificador del evento (sirve para detectar duplicados)
 * @param ordenId           la orden que cambió
 * @param usuarioId         dueño de la orden
 * @param simbolo           acción; es la clave del mensaje
 * @param estado            ABIERTA, PARCIAL, EJECUTADA o CANCELADA
 * @param cantidad          acciones que pide la orden
 * @param cantidadEjecutada acciones ejecutadas en total hasta este evento
 * @param ejecucion         la ejecución que produjo este evento; null si no hubo (entró al libro o se canceló)
 * @param motivo            por qué se canceló; null en los demás casos
 * @param ocurridoEn        momento del cambio
 */
public record EventoOrden(
        UUID eventoId,
        UUID ordenId,
        String usuarioId,
        String simbolo,
        EstadoOrden estado,
        int cantidad,
        int cantidadEjecutada,
        EjecucionOrden ejecucion,
        String motivo,
        Instant ocurridoEn) {
}
