package co.bolsasim.portfolio.kafka;

import co.bolsasim.eventos.OperacionEjecutada;
import co.bolsasim.eventos.Topicos;
import co.bolsasim.portfolio.precio.RepositorioPrecios;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Escucha trades.executed y guarda el precio de cada operación como último precio del símbolo: con él se valora el
 * portafolio (RF-32, RF-33). La liquidación no lo usa; liquida con orders.events.
 *
 * <p>No hace falta EVENTO_PROCESADO: guardar el mismo precio dos veces deja lo mismo, y una operación más vieja
 * que la copia no la reemplaza.
 */
@Component
public class OperacionEjecutadaListener {

    private static final Logger log = LoggerFactory.getLogger(OperacionEjecutadaListener.class);

    private final RepositorioPrecios precios;

    public OperacionEjecutadaListener(RepositorioPrecios precios) {
        this.precios = precios;
    }

    @KafkaListener(topics = Topicos.TRADES_EXECUTED, groupId = "portfolio-service")
    public void recibir(OperacionEjecutada operacion) {
        if (precios.actualizar(operacion.simbolo(), operacion.precio(), operacion.ejecutadaEn())) {
            log.info("Último precio de {}: {} COP", operacion.simbolo(), operacion.precio());
        } else {
            log.info("Operación {} de {} sin efecto: ya hay un precio más nuevo", operacion.operacionId(),
                    operacion.simbolo());
        }
    }
}
