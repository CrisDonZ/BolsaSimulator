package co.bolsasim.order.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.bolsasim.eventos.EstadoInstrumento;
import co.bolsasim.eventos.InstrumentoActualizado;
import co.bolsasim.order.instrumento.Instrumento;
import co.bolsasim.order.instrumento.RepositorioInstrumentos;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Prueba unitaria del listener: se llama al método directamente, sin Kafka ni base de datos. */
class InstrumentoActualizadoListenerTest {

    private static final Instant ANTES = Instant.parse("2026-10-20T10:00:00Z");
    private static final Instant DESPUES = Instant.parse("2026-10-20T10:05:00Z");

    private final RepositorioInstrumentos repositorio = mock(RepositorioInstrumentos.class);
    private final InstrumentoActualizadoListener listener = new InstrumentoActualizadoListener(repositorio);

    @Test
    void unInstrumentoNuevoSeGuardaConSuEstadoYSuTick() {
        listener.recibir(evento(EstadoInstrumento.ACTIVO, ANTES));

        ArgumentCaptor<Instrumento> guardado = ArgumentCaptor.forClass(Instrumento.class);
        verify(repositorio).save(guardado.capture());
        assertThat(guardado.getValue().getSimbolo()).isEqualTo("ANDX");
        assertThat(guardado.getValue().activo()).isTrue();
        assertThat(guardado.getValue().getTick()).isEqualTo(10);
    }

    @Test
    void unaSuspensionReemplazaLaCopia() {
        Instrumento copia = new Instrumento(evento(EstadoInstrumento.ACTIVO, ANTES));
        when(repositorio.findById("ANDX")).thenReturn(Optional.of(copia));

        listener.recibir(evento(EstadoInstrumento.SUSPENDIDO, DESPUES));

        assertThat(copia.getEstado()).isEqualTo(EstadoInstrumento.SUSPENDIDO);
        assertThat(copia.getActualizadoEn()).isEqualTo(DESPUES);
        verify(repositorio, never()).save(any());
    }

    @Test
    void unEventoRepetidoDejaLaCopiaIgual() {
        Instrumento copia = new Instrumento(evento(EstadoInstrumento.SUSPENDIDO, DESPUES));
        when(repositorio.findById("ANDX")).thenReturn(Optional.of(copia));

        listener.recibir(evento(EstadoInstrumento.SUSPENDIDO, DESPUES));

        assertThat(copia.getEstado()).isEqualTo(EstadoInstrumento.SUSPENDIDO);
        assertThat(copia.getActualizadoEn()).isEqualTo(DESPUES);
    }

    @Test
    void unEventoMasViejoQueLaCopiaNoReactivaUnInstrumentoSuspendido() {
        Instrumento copia = new Instrumento(evento(EstadoInstrumento.SUSPENDIDO, DESPUES));
        when(repositorio.findById("ANDX")).thenReturn(Optional.of(copia));

        listener.recibir(evento(EstadoInstrumento.ACTIVO, ANTES));

        assertThat(copia.getEstado()).isEqualTo(EstadoInstrumento.SUSPENDIDO);
        assertThat(copia.getActualizadoEn()).isEqualTo(DESPUES);
    }

    private static InstrumentoActualizado evento(EstadoInstrumento estado, Instant actualizadoEn) {
        return new InstrumentoActualizado("ANDX", "Andex Energía", "Energía", 5_000, 10, 1_000_000, estado,
                actualizadoEn);
    }
}
