package co.bolsasim.auth.servicio;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.bolsasim.eventos.Topicos;
import co.bolsasim.eventos.UsuarioRegistrado;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Prueba unitaria del publicador: KafkaTemplate es un doble de Mockito. Las pruebas con transacción
 * simulan a mano lo que hace Spring al confirmar (afterCommit) o deshacer (afterCompletion con rollback).
 */
class PublicadorUsuariosTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, UsuarioRegistrado> kafkaTemplate = mock(KafkaTemplate.class);
    private final PublicadorUsuarios publicador = new PublicadorUsuarios(kafkaTemplate);
    private final UsuarioRegistrado evento = UsuarioRegistrado.de(UUID.randomUUID(), "ana_inv", "INVERSIONISTA",
            Instant.now());

    @BeforeEach
    void kafkaRespondeSinTerminar() {
        // Un envío que nunca termina: basta para saber si se llamó a send
        when(kafkaTemplate.send(anyString(), anyString(), any())).thenReturn(new CompletableFuture<>());
    }

    @AfterEach
    void cerrarTransaccionSimulada() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publicaEnUsersRegisteredConElIdDelUsuarioComoClave() {
        publicador.publicar(evento);

        verify(kafkaTemplate).send(Topicos.USERS_REGISTERED, evento.usuarioId().toString(), evento);
    }

    @Test
    void dentroDeUnaTransaccionEsperaAlCommitParaPublicar() {
        TransactionSynchronizationManager.initSynchronization();

        publicador.publicar(evento);
        verifyNoInteractions(kafkaTemplate);

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(kafkaTemplate).send(Topicos.USERS_REGISTERED, evento.usuarioId().toString(), evento);
    }

    @Test
    void siLaTransaccionSeDeshaceNoPublica() {
        TransactionSynchronizationManager.initSynchronization();

        publicador.publicar(evento);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        verifyNoInteractions(kafkaTemplate);
    }
}
