package co.bolsasim.marketdata.kafka;

import co.bolsasim.eventos.OperacionEjecutada;
import co.bolsasim.eventos.Topicos;
import co.bolsasim.marketdata.operacion.RepositorioOperaciones;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Escucha trades.executed y guarda cada operación en OPERACION: de ahí salen el último precio y la variación del
 * día (HU-14, RF-11, RF-40).
 *
 * <p>Es un listener normal y no Kafka Streams: guardar operaciones no necesita ventanas ni agregados. Streams
 * entra con las velas (HU-15). No hace falta EVENTO_PROCESADO: la llave de OPERACION es el id de la operación, y
 * una repetida no se guarda.
 */
@Component
public class OperacionEjecutadaListener {

    private static final Logger log = LoggerFactory.getLogger(OperacionEjecutadaListener.class);

    private final RepositorioOperaciones operaciones;

    public OperacionEjecutadaListener(RepositorioOperaciones operaciones) {
        this.operaciones = operaciones;
    }

    @KafkaListener(topics = Topicos.TRADES_EXECUTED, groupId = "market-data-service")
    public void recibir(OperacionEjecutada operacion) {
        if (operaciones.guardar(operacion)) {
            log.info("Operación de {} guardada: {} acciones a {} COP", operacion.simbolo(), operacion.cantidad(),
                    operacion.precio());
        } else {
            log.info("Operación {} de {} sin efecto: ya estaba guardada o el símbolo no es un instrumento",
                    operacion.operacionId(), operacion.simbolo());
        }
    }
}
