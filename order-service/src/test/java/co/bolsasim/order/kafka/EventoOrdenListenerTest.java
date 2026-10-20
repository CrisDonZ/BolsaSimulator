package co.bolsasim.order.kafka;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import co.bolsasim.eventos.EstadoOrden;
import co.bolsasim.eventos.EventoOrden;
import co.bolsasim.order.orden.EventoImposibleException;
import co.bolsasim.order.servicio.ServicioEventosOrden;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** Prueba unitaria del listener: qué errores detienen el consumo y cuáles no. */
class EventoOrdenListenerTest {

    private final ServicioEventosOrden servicio = mock(ServicioEventosOrden.class);
    private final EventoOrdenListener listener = new EventoOrdenListener(servicio);
    private final EventoOrden evento = new EventoOrden(UUID.randomUUID(), UUID.randomUUID(), "ana", "ANDX",
            EstadoOrden.EJECUTADA, 100, 120, null, null, Instant.now());

    @Test
    void unEventoImposibleNoTrabaElConsumo() {
        when(servicio.aplicar(evento)).thenThrow(new EventoImposibleException("se ejecutarían 120 de 100 acciones"));

        // Se registra como ERROR y el listener sigue con el siguiente evento
        assertThatCode(() -> listener.recibir(evento)).doesNotThrowAnyException();
    }

    @Test
    void cualquierOtroErrorSeRelanzaParaQueKafkaLoVuelvaAEntregar() {
        when(servicio.aplicar(evento)).thenThrow(new DataAccessResourceFailureException("la base no responde"));

        assertThatThrownBy(() -> listener.recibir(evento)).isInstanceOf(DataAccessResourceFailureException.class);
    }
}
