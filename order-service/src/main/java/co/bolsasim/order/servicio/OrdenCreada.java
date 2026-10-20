package co.bolsasim.order.servicio;

import co.bolsasim.order.orden.Orden;

/**
 * Lo que devuelve crear una orden.
 *
 * @param repetida true si la solicitud ya había llegado antes con la misma Idempotency-Key: la orden es la
 *                 original y no se creó nada nuevo
 */
public record OrdenCreada(Orden orden, boolean repetida) {
}
