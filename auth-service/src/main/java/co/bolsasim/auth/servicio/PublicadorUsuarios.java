package co.bolsasim.auth.servicio;

import co.bolsasim.eventos.Topicos;
import co.bolsasim.eventos.UsuarioRegistrado;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Publica en Kafka los eventos de usuarios. */
@Component
public class PublicadorUsuarios {

    private static final Logger log = LoggerFactory.getLogger(PublicadorUsuarios.class);

    private final KafkaTemplate<String, UsuarioRegistrado> kafkaTemplate;

    public PublicadorUsuarios(KafkaTemplate<String, UsuarioRegistrado> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Si hay una transacción abierta, el evento sale solo cuando la base confirma el commit:
     * si el registro se deshace (rollback), nadie se entera de un usuario que no existe.
     *
     * <p>Queda un caso sin cubrir: si Kafka está caído justo después del commit, el usuario
     * existe pero el evento se pierde. Lo resuelve el patrón outbox del modelo entidad-relación.
     */
    public void publicar(UsuarioRegistrado evento) {
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

    /** La clave es el id del usuario: todos los eventos de un usuario van a la misma partición, en orden. */
    private void enviar(UsuarioRegistrado evento) {
        kafkaTemplate.send(Topicos.USERS_REGISTERED, evento.usuarioId().toString(), evento)
                .whenComplete((resultado, error) -> {
                    if (error != null) {
                        log.error("No se pudo publicar el registro del usuario {}", evento.usuarioId(), error);
                        return;
                    }
                    RecordMetadata meta = resultado.getRecordMetadata();
                    log.info("Usuario {} publicado en {} (partición {}, offset {})",
                            evento.usuarioId(), meta.topic(), meta.partition(), meta.offset());
                });
    }
}
