package co.bolsasim.order.servicio;

import co.bolsasim.eventos.EventoOrden;
import co.bolsasim.order.kafka.EventosProcesados;
import co.bolsasim.order.orden.EventoImposibleException;
import co.bolsasim.order.orden.Orden;
import co.bolsasim.order.orden.RegistroEjecuciones;
import co.bolsasim.order.orden.RepositorioOrdenes;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aplica a cada orden lo que el motor publica en orders.events: su estado, la cantidad ejecutada, el precio
 * promedio y el motivo (HU-09, RF-20).
 *
 * <p>Todo en una transacción por evento: marcar el evento como procesado, guardar la ejecución, recalcular el
 * precio promedio y actualizar la orden. Si algo falla, no queda nada a medias, y el evento se puede volver a
 * aplicar. Si el evento ya se había aplicado, no hace nada (RNF-09).
 */
@Service
public class ServicioEventosOrden {

    private static final Logger log = LoggerFactory.getLogger(ServicioEventosOrden.class);

    private final EventosProcesados procesados;
    private final RepositorioOrdenes ordenes;
    private final RegistroEjecuciones ejecuciones;

    public ServicioEventosOrden(EventosProcesados procesados, RepositorioOrdenes ordenes,
                                RegistroEjecuciones ejecuciones) {
        this.procesados = procesados;
        this.ordenes = ordenes;
        this.ejecuciones = ejecuciones;
    }

    /**
     * @return false si el evento ya se había aplicado
     * @throws EventoImposibleException si el evento no tiene sentido para la orden; no se aplica nada
     */
    @Transactional
    public boolean aplicar(EventoOrden evento) {
        if (!procesados.marcar(evento.eventoId())) {
            log.info("Evento {} de la orden {} repetido: ya se había aplicado", evento.eventoId(), evento.ordenId());
            return false;
        }
        Orden orden = ordenes.findById(evento.ordenId())
                .orElseThrow(() -> new EventoImposibleException("la orden no existe en order-service"));
        if (!orden.getUsuarioId().toString().equals(evento.usuarioId())) {
            throw new EventoImposibleException("el evento es de otro usuario");
        }

        if (evento.ejecucion() != null) {
            ejecuciones.registrar(orden.getId(), evento.ejecucion(), evento.ocurridoEn());
        }
        BigDecimal precioPromedio = ejecuciones.precioPromedio(orden.getId());
        orden.aplicar(evento.estado(), evento.cantidad(), evento.cantidadEjecutada(), precioPromedio, evento.motivo(),
                Instant.now().truncatedTo(ChronoUnit.MICROS));

        log.info("Orden {} ahora está {} ({} de {} ejecutadas, precio promedio {})", orden.getId(), orden.getEstado(),
                orden.getCantidadEjecutada(), orden.getCantidad(), orden.getPrecioPromedio());
        return true;
    }
}
