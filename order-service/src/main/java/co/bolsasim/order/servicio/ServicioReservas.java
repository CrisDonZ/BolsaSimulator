package co.bolsasim.order.servicio;

import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.ReservaProcesada;
import co.bolsasim.eventos.ResultadoReserva;
import co.bolsasim.eventos.Topicos;
import co.bolsasim.order.kafka.EventosProcesados;
import co.bolsasim.order.orden.EventoImposibleException;
import co.bolsasim.order.orden.Orden;
import co.bolsasim.order.orden.RepositorioOrdenes;
import co.bolsasim.order.outbox.Outbox;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La segunda mitad de la saga de la reserva (RF-17, RF-18): order-service pidió la reserva al crear la orden
 * (orders.requested) y aquí aplica la respuesta de portfolio-service (reservations.events).
 *
 * <ul>
 *   <li>ACEPTADA: el comando NUEVA de la orden queda en el outbox para el motor. La orden sigue PENDIENTE hasta que
 *       el motor diga qué pasó con ella.</li>
 *   <li>RECHAZADA: la orden queda RECHAZADA con el motivo, y nunca llega al motor.</li>
 * </ul>
 *
 * <p>Todo en una transacción por evento, como orders.events: la marca del evento, el cambio de la orden y el
 * comando del outbox se guardan juntos o no se guarda nada. Un evento repetido no hace nada (RNF-09).
 */
@Service
public class ServicioReservas {

    private static final Logger log = LoggerFactory.getLogger(ServicioReservas.class);

    private final EventosProcesados procesados;
    private final RepositorioOrdenes ordenes;
    private final Outbox outbox;

    public ServicioReservas(EventosProcesados procesados, RepositorioOrdenes ordenes, Outbox outbox) {
        this.procesados = procesados;
        this.ordenes = ordenes;
        this.outbox = outbox;
    }

    /**
     * @return false si el evento ya se había aplicado
     * @throws EventoImposibleException si la orden no existe, es de otro usuario o ya no está PENDIENTE; no se
     *                                  aplica nada
     */
    @Transactional
    public boolean aplicar(ReservaProcesada evento) {
        if (!procesados.marcar(evento.eventoId())) {
            log.info("Reserva {} de la orden {} repetida: ya se había aplicado", evento.eventoId(), evento.ordenId());
            return false;
        }
        Orden orden = ordenes.findById(evento.ordenId())
                .orElseThrow(() -> new EventoImposibleException("la orden no existe en order-service"));
        if (!orden.getUsuarioId().toString().equals(evento.usuarioId())) {
            throw new EventoImposibleException("la reserva es de otro usuario");
        }

        if (evento.resultado() == ResultadoReserva.ACEPTADA) {
            ComandoOrden comando = orden.comandoParaElMotor();
            // La clave del mensaje es el símbolo: el motor recibe las órdenes de ANDX en el orden en que se aceptaron
            outbox.guardar(Topicos.ORDERS_COMMANDS, comando.simbolo(), comando);
            log.info("Reserva de la orden {} aceptada ({}): va al motor", orden.getId(), evento.montoReservado());
        } else {
            orden.rechazar(evento.motivo(), Instant.now().truncatedTo(ChronoUnit.MICROS));
            log.info("Orden {} RECHAZADA: {}", orden.getId(), evento.motivo());
        }
        return true;
    }
}
