package co.bolsasim.eventos;

import java.time.Instant;
import java.util.UUID;

/**
 * Respuesta de portfolio-service a una {@link SolicitudReserva}, en {@link Topicos#RESERVATIONS_EVENTS}. Con
 * ACEPTADA, order-service envía la orden al motor; con RECHAZADA, la deja RECHAZADA con el motivo (RF-18).
 *
 * @param eventoId       identificador del evento (sirve para detectar duplicados)
 * @param ordenId        la orden de la solicitud
 * @param usuarioId      dueño de la orden; es la clave del mensaje
 * @param resultado      ACEPTADA o RECHAZADA
 * @param montoReservado COP o acciones reservadas; 0 si se rechazó
 * @param motivo         por qué se rechazó, para mostrárselo al usuario; null si se aceptó
 * @param procesadaEn    momento en que portfolio-service la procesó
 */
public record ReservaProcesada(
        UUID eventoId,
        UUID ordenId,
        String usuarioId,
        ResultadoReserva resultado,
        long montoReservado,
        String motivo,
        Instant procesadaEn) {

    public static ReservaProcesada aceptada(SolicitudReserva solicitud, long montoReservado, Instant ahora) {
        return new ReservaProcesada(UUID.randomUUID(), solicitud.ordenId(), solicitud.usuarioId(),
                ResultadoReserva.ACEPTADA, montoReservado, null, ahora);
    }

    public static ReservaProcesada rechazada(SolicitudReserva solicitud, String motivo, Instant ahora) {
        return new ReservaProcesada(UUID.randomUUID(), solicitud.ordenId(), solicitud.usuarioId(),
                ResultadoReserva.RECHAZADA, 0, motivo, ahora);
    }
}
