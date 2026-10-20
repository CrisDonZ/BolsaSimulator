package co.bolsasim.eventos;

import java.util.UUID;

/**
 * La parte de una operación que le toca a una orden: va dentro de {@link EventoOrden}.
 *
 * @param operacionId la operación de {@link Topicos#TRADES_EXECUTED} de la que sale esta ejecución
 * @param precio      precio en COP al que se ejecutó
 * @param cantidad    acciones ejecutadas en esta operación
 */
public record EjecucionOrden(UUID operacionId, long precio, int cantidad) {
}
