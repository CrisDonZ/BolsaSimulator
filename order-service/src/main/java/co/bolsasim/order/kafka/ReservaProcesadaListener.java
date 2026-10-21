package co.bolsasim.order.kafka;

import co.bolsasim.eventos.ReservaProcesada;
import co.bolsasim.eventos.Topicos;
import co.bolsasim.order.orden.EventoImposibleException;
import co.bolsasim.order.servicio.ServicioReservas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Escucha reservations.events: con la reserva aceptada la orden va al motor; con la rechazada queda RECHAZADA.
 */
@Component
public class ReservaProcesadaListener {

    private static final Logger log = LoggerFactory.getLogger(ReservaProcesadaListener.class);

    private final ServicioReservas servicio;

    public ReservaProcesadaListener(ServicioReservas servicio) {
        this.servicio = servicio;
    }

    /**
     * Como en orders.events: un evento imposible queda en el log y no traba al listener; los demás errores se
     * relanzan para que Kafka lo vuelva a entregar.
     */
    @KafkaListener(topics = Topicos.RESERVATIONS_EVENTS, groupId = "order-service")
    public void recibir(ReservaProcesada evento) {
        try {
            servicio.aplicar(evento);
        } catch (EventoImposibleException e) {
            log.error("Reserva {} de la orden {} no se aplica: {}. Evento: {}", evento.eventoId(), evento.ordenId(),
                    e.getMessage(), evento);
        }
    }
}
