package co.bolsasim.marketdata.servicio;

import co.bolsasim.eventos.InstrumentoActualizado;
import co.bolsasim.eventos.Topicos;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Publica en Kafka los cambios de los instrumentos. */
@Component
public class PublicadorInstrumentos {

    private static final Logger log = LoggerFactory.getLogger(PublicadorInstrumentos.class);

    private final KafkaTemplate<String, InstrumentoActualizado> kafkaTemplate;

    public PublicadorInstrumentos(KafkaTemplate<String, InstrumentoActualizado> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Si hay una transacción abierta, el evento sale solo cuando la base confirma el commit:
     * si el cambio se deshace (rollback), nadie se entera de algo que no pasó.
     *
     * <p>Si Kafka está caído justo después del commit, el evento se pierde; lo repara la siguiente
     * vez que market-data-service arranca, porque vuelve a publicar todo el catálogo. La solución de
     * fondo es el patrón outbox del modelo entidad-relación.
     */
    public void publicar(InstrumentoActualizado evento) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    enviar(evento);
                }
            });
        } else {
            enviar(evento);
        }
    }

    /**
     * La clave es el símbolo: todos los cambios de un instrumento van a la misma partición, en orden,
     * y la compactación conserva el último de cada uno.
     */
    private void enviar(InstrumentoActualizado evento) {
        kafkaTemplate.send(Topicos.INSTRUMENTS_EVENTS, evento.simbolo(), evento)
                .whenComplete((resultado, error) -> {
                    if (error != null) {
                        log.error("No se pudo publicar el instrumento {}", evento.simbolo(), error);
                        return;
                    }
                    RecordMetadata meta = resultado.getRecordMetadata();
                    log.info("Instrumento {} ({}) publicado en {} (partición {}, offset {})",
                            evento.simbolo(), evento.estado(), meta.topic(), meta.partition(), meta.offset());
                });
    }
}
