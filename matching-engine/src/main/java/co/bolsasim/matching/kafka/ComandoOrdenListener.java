package co.bolsasim.matching.kafka;

import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.Topicos;
import co.bolsasim.matching.libro.Ejecucion;
import co.bolsasim.matching.motor.MotorEmparejamiento;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Escucha el tópico orders.commands y entrega cada orden al libro de su símbolo.
 * Por ahora las ejecuciones solo se muestran en el log.
 */
@Component
public class ComandoOrdenListener {

    private static final Logger log = LoggerFactory.getLogger(ComandoOrdenListener.class);

    private final MotorEmparejamiento motor;

    public ComandoOrdenListener(MotorEmparejamiento motor) {
        this.motor = motor;
    }

    @KafkaListener(topics = Topicos.ORDERS_COMMANDS, groupId = "matching-engine")
    public void recibir(ConsumerRecord<String, ComandoOrden> registro) {
        ComandoOrden comando = registro.value();
        String precio = comando.precioLimite() == null ? "a mercado" : "a " + comando.precioLimite() + " COP";

        log.info("Orden recibida: {} {} {} acciones de {} {} | partición {}, offset {}",
                comando.accion(), comando.lado(), comando.cantidad(), comando.simbolo(), precio,
                registro.partition(), registro.offset());

        List<Ejecucion> ejecuciones = motor.procesar(comando);
        for (Ejecucion e : ejecuciones) {
            log.info("Ejecución: {} acciones de {} a {} COP | compra {} ({}) - venta {} ({})",
                    e.cantidad(), e.simbolo(), e.precio(),
                    e.ordenCompraId(), e.compradorId(), e.ordenVentaId(), e.vendedorId());
        }
        log.info("Libro {}", motor.resumen(comando.simbolo()));
    }
}
