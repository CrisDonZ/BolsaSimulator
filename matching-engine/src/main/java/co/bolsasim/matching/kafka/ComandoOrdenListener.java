package co.bolsasim.matching.kafka;

import co.bolsasim.eventos.AccionComando;
import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.TipoOrden;
import co.bolsasim.eventos.Topicos;
import co.bolsasim.matching.libro.Ejecucion;
import co.bolsasim.matching.motor.Mensaje;
import co.bolsasim.matching.motor.MotorEmparejamiento;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Lee los comandos de orders.commands, los aplica al libro y publica lo que pasó en orders.events y
 * trades.executed (RF-28).
 *
 * <p>Cada comando se procesa dentro de una transacción de Kafka (RNF-10, ver application.yml): los mensajes
 * publicados y la confirmación del offset del comando quedan juntos. Si el motor se cae a mitad de camino, la
 * transacción se aborta, nadie ve esos mensajes (los consumidores leen con read_committed) y el comando se
 * vuelve a entregar.
 */
@Component
public class ComandoOrdenListener {

    private static final Logger log = LoggerFactory.getLogger(ComandoOrdenListener.class);

    private final MotorEmparejamiento motor;
    private final KafkaTemplate<String, Object> kafka;

    public ComandoOrdenListener(MotorEmparejamiento motor, KafkaTemplate<String, Object> kafka) {
        this.motor = motor;
        this.kafka = kafka;
    }

    @KafkaListener(topics = Topicos.ORDERS_COMMANDS, groupId = "matching-engine")
    public void recibir(ConsumerRecord<String, ComandoOrden> registro) {
        ComandoOrden comando = registro.value();
        String precio = comando.precioLimite() == null
                ? "a mercado (referencia " + comando.precioReferencia() + " COP)"
                : "a " + comando.precioLimite() + " COP";

        log.info("Orden recibida: {} {} {} acciones de {} {} | partición {}, offset {}",
                comando.accion(), comando.lado(), comando.cantidad(), comando.simbolo(), precio,
                registro.partition(), registro.offset());
        if (comando.accion() == AccionComando.NUEVA && comando.tipo() == TipoOrden.MERCADO
                && (comando.precioReferencia() == null || comando.precioReferencia() <= 0)) {
            // Un comando de antes de HU-07 o un error de order-service: sin banda no se ejecuta (RF-26)
            log.warn("Orden {} a mercado sin precio de referencia válido: se cancela sin tocar el libro",
                    comando.ordenId());
        }

        MotorEmparejamiento.Procesado procesado = motor.procesar(comando);
        if (procesado.repetido()) {
            log.info("Comando {} de la orden {} repetido: el libro no cambia y se reenvían sus {} mensajes",
                    comando.accion(), comando.ordenId(), procesado.mensajes().size());
        }
        for (Ejecucion e : procesado.ejecuciones()) {
            log.info("Ejecución: {} acciones de {} a {} COP | compra {} ({}) - venta {} ({})",
                    e.cantidad(), e.simbolo(), e.precio(),
                    e.ordenCompraId(), e.compradorId(), e.ordenVentaId(), e.vendedorId());
        }

        // Dentro de la transacción del listener: salen todos o ninguno, junto con el offset del comando
        for (Mensaje mensaje : procesado.mensajes()) {
            kafka.send(mensaje.topico(), mensaje.clave(), mensaje.evento());
        }
        log.info("Libro {} | {} mensajes publicados", motor.resumen(comando.simbolo()), procesado.mensajes().size());
    }
}
