package co.bolsasim.portfolio.kafka;

import co.bolsasim.eventos.EventoOrden;
import co.bolsasim.eventos.Topicos;
import co.bolsasim.portfolio.servicio.EventoImposibleException;
import co.bolsasim.portfolio.servicio.ServicioLiquidacion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Escucha orders.events y liquida las compras: paga cada ejecución con su reserva y libera lo que sobra (RF-30,
 * RF-31). Solo lee eventos de transacciones confirmadas del motor (read_committed, ver application.yml).
 */
@Component
public class EventoOrdenListener {

    private static final Logger log = LoggerFactory.getLogger(EventoOrdenListener.class);

    private final ServicioLiquidacion servicio;

    public EventoOrdenListener(ServicioLiquidacion servicio) {
        this.servicio = servicio;
    }

    /**
     * Un evento imposible no se aplica: queda un ERROR en el log y el listener sigue con el siguiente, en vez de
     * trabarse reintentándolo para siempre. Cualquier otro error (por ejemplo, la base caída) se relanza para que
     * Kafka lo vuelva a entregar.
     */
    @KafkaListener(topics = Topicos.ORDERS_EVENTS, groupId = "portfolio-service")
    public void recibir(EventoOrden evento) {
        try {
            servicio.aplicar(evento);
        } catch (EventoImposibleException e) {
            log.error("Evento {} de la orden {} no se liquida: {}. Evento: {}", evento.eventoId(), evento.ordenId(),
                    e.getMessage(), evento);
        }
    }
}
