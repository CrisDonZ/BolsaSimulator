package co.bolsasim.portfolio.kafka;

import co.bolsasim.eventos.SolicitudReserva;
import co.bolsasim.eventos.Topicos;
import co.bolsasim.portfolio.servicio.ServicioReservas;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Escucha orders.requested y reserva el saldo de cada orden nueva. Las solicitudes de un usuario llegan por una
 * sola partición, en el orden en que las creó.
 */
@Component
public class SolicitudReservaListener {

    private final ServicioReservas servicio;

    public SolicitudReservaListener(ServicioReservas servicio) {
        this.servicio = servicio;
    }

    @KafkaListener(topics = Topicos.ORDERS_REQUESTED, groupId = "portfolio-service")
    public void recibir(SolicitudReserva solicitud) {
        servicio.procesar(solicitud);
    }
}
