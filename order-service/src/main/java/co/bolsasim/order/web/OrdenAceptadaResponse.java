package co.bolsasim.order.web;

import java.util.UUID;

/** Respuesta de POST /api/ordenes: la orden fue aceptada para procesarse. */
public record OrdenAceptadaResponse(UUID ordenId, String simbolo, String mensaje) {
}
