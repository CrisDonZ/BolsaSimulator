package co.bolsasim.order.kafka;

import co.bolsasim.eventos.EventoOrden;
import co.bolsasim.eventos.Topicos;
import co.bolsasim.order.orden.EventoImposibleException;
import co.bolsasim.order.servicio.ServicioEventosOrden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Escucha orders.events y actualiza cada orden con lo que hizo el motor. Los eventos de un símbolo llegan por
 * una sola partición, en el orden en que el motor los publicó; y solo los de transacciones confirmadas
 * (read_committed, ver application.yml).
 */
@Component
public class EventoOrdenListener {

    private static final Logger log = LoggerFactory.getLogger(EventoOrdenListener.class);

    private final ServicioEventosOrden servicio;

    public EventoOrdenListener(ServicioEventosOrden servicio) {
        this.servicio = servicio;
    }

    /**
     * Un evento imposible no se aplica: queda un ERROR en el log y el listener sigue con el siguiente, en vez de
     * trabarse reintentándolo para siempre. Cualquier otro error (por ejemplo, la base caída) se relanza para
     * que Kafka lo vuelva a entregar.
     */
    @KafkaListener(topics = Topicos.ORDERS_EVENTS, groupId = "order-service")
    public void recibir(EventoOrden evento) {
        try {
            servicio.aplicar(evento);
        } catch (EventoImposibleException e) {
            log.error("Evento {} de la orden {} no se aplica: {}. Evento: {}", evento.eventoId(), evento.ordenId(),
                    e.getMessage(), evento);
        }
    }
}
