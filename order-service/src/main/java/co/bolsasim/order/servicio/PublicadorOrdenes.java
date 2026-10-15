package co.bolsasim.order.servicio;

import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.Topicos;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/** Publica los comandos de órdenes en Kafka. */
@Component
public class PublicadorOrdenes {

    private static final Logger log = LoggerFactory.getLogger(PublicadorOrdenes.class);

    private final KafkaTemplate<String, ComandoOrden> kafkaTemplate;

    public PublicadorOrdenes(KafkaTemplate<String, ComandoOrden> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * La clave del mensaje es el símbolo. Kafka manda todos los mensajes con la misma
     * clave a la misma partición, así el motor recibe las órdenes de ANDX en el orden
     * exacto en que llegaron.
     */
    public void publicar(ComandoOrden comando) {
        kafkaTemplate.send(Topicos.ORDERS_COMMANDS, comando.simbolo(), comando)
                .whenComplete((resultado, error) -> {
                    if (error != null) {
                        log.error("No se pudo publicar la orden {}", comando.ordenId(), error);
                        return;
                    }
                    RecordMetadata meta = resultado.getRecordMetadata();
                    log.info("Orden {} publicada en {} (clave {}, partición {}, offset {})",
                            comando.ordenId(), meta.topic(), comando.simbolo(), meta.partition(), meta.offset());
                });
    }
}
