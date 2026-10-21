package co.bolsasim.order.kafka;

import co.bolsasim.eventos.OperacionEjecutada;
import co.bolsasim.eventos.Topicos;
import co.bolsasim.order.instrumento.RepositorioInstrumentos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Escucha trades.executed y guarda el precio de cada operación como último precio del instrumento: es la referencia
 * de la banda de ±10 % (RF-16) y de la reserva de las compras a mercado (RF-17).
 *
 * <p>No hace falta EVENTO_PROCESADO: guardar el mismo precio dos veces deja lo mismo, y una operación más vieja
 * que la copia no la reemplaza.
 */
@Component
public class OperacionEjecutadaListener {

    private static final Logger log = LoggerFactory.getLogger(OperacionEjecutadaListener.class);

    private final RepositorioInstrumentos repositorio;

    public OperacionEjecutadaListener(RepositorioInstrumentos repositorio) {
        this.repositorio = repositorio;
    }

    @KafkaListener(topics = Topicos.TRADES_EXECUTED, groupId = "order-service")
    @Transactional
    public void recibir(OperacionEjecutada operacion) {
        if (repositorio.actualizarUltimoPrecio(operacion.simbolo(), operacion.precio(), operacion.ejecutadaEn()) == 1) {
            log.info("Último precio de {}: {} COP", operacion.simbolo(), operacion.precio());
        } else {
            log.info("Operación {} de {} sin efecto: el instrumento no está en la copia local o ya tiene un precio "
                    + "más nuevo", operacion.operacionId(), operacion.simbolo());
        }
    }
}
