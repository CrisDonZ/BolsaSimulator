package co.bolsasim.marketdata.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.bolsasim.eventos.EstadoInstrumento;
import co.bolsasim.eventos.InstrumentoActualizado;
import co.bolsasim.marketdata.instrumento.Instrumento;
import co.bolsasim.marketdata.instrumento.RepositorioInstrumentos;
import co.bolsasim.marketdata.web.CrearInstrumentoRequest;
import co.bolsasim.marketdata.web.EditarInstrumentoRequest;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Prueba unitaria: no levanta Spring, Kafka ni base de datos. El repositorio y el publicador son dobles
 * de Mockito, así se ve qué evento saldría hacia instruments.events después de cada cambio.
 */
class ServicioInstrumentosTest {

    private static final Instant AYER = Instant.now().minusSeconds(86_400);

    private final RepositorioInstrumentos repositorio = mock(RepositorioInstrumentos.class);
    private final PublicadorInstrumentos publicador = mock(PublicadorInstrumentos.class);
    private final ServicioInstrumentos servicio = new ServicioInstrumentos(repositorio, publicador);

    @Test
    void crearGuardaElInstrumentoActivoYPublicaSuEstadoCompleto() {
        when(repositorio.saveAndFlush(any())).thenAnswer(invocacion -> invocacion.getArgument(0));

        Instrumento creado = servicio.crear(new CrearInstrumentoRequest(" nuevo ", " Nueva Empresa ", " Energía ",
                5_000L, 10L, 1_000_000L));

        assertThat(creado.getSimbolo()).isEqualTo("NUEVO");
        assertThat(creado.getNombre()).isEqualTo("Nueva Empresa");
        assertThat(creado.getEstado()).isEqualTo(EstadoInstrumento.ACTIVO);
        InstrumentoActualizado evento = publicado();
        assertThat(evento).isEqualTo(new InstrumentoActualizado("NUEVO", "Nueva Empresa", "Energía", 5_000, 10,
                1_000_000, EstadoInstrumento.ACTIVO, creado.getActualizadoEn()));
    }

    @Test
    void crearUnSimboloQueYaExisteSeRechazaYNoPublicaNada() {
        when(repositorio.existsById("ANDX")).thenReturn(true);

        assertThatThrownBy(() -> servicio.crear(new CrearInstrumentoRequest("andx", "Otra", "Energía", 5_000L, 10L, 100L)))
                .isInstanceOf(InstrumentoDuplicadoException.class)
                .hasMessage("Ese símbolo ya existe");
        verify(repositorio, never()).saveAndFlush(any());
        verifyNoInteractions(publicador);
    }

    @Test
    void unPrecioInicialQueNoEsMultiploDelTickSeRechaza() {
        assertThatThrownBy(() -> servicio.crear(new CrearInstrumentoRequest("NUEVO", "Nueva", "Energía", 5_003L, 10L, 100L)))
                .isInstanceOf(InstrumentoInvalidoException.class)
                .hasMessageContaining("múltiplo del tick (10)");
        verify(repositorio, never()).saveAndFlush(any());
        verifyNoInteractions(publicador);
    }

    @Test
    void editarCambiaNombreYSectorYPublicaSinTocarLoDemas() {
        Instrumento andx = existente(EstadoInstrumento.ACTIVO);

        servicio.editar("andx", new EditarInstrumentoRequest("Andex Energía S.A.", "Energía renovable"));

        InstrumentoActualizado evento = publicado();
        assertThat(evento.nombre()).isEqualTo("Andex Energía S.A.");
        assertThat(evento.sector()).isEqualTo("Energía renovable");
        assertThat(evento.tick()).isEqualTo(10);
        assertThat(evento.precioInicial()).isEqualTo(5_000);
        assertThat(evento.accionesEmitidas()).isEqualTo(1_000_000);
        assertThat(evento.actualizadoEn()).isAfter(AYER);
        assertThat(andx.getCreadoEn()).isEqualTo(AYER);
    }

    @Test
    void editarSinCambiarNadaNoPublica() {
        existente(EstadoInstrumento.ACTIVO);

        servicio.editar("ANDX", new EditarInstrumentoRequest("Andex Energía", "Energía"));

        verifyNoInteractions(publicador);
    }

    @Test
    void suspenderPublicaElInstrumentoSuspendido() {
        Instrumento andx = existente(EstadoInstrumento.ACTIVO);

        servicio.suspender("ANDX");

        assertThat(andx.getEstado()).isEqualTo(EstadoInstrumento.SUSPENDIDO);
        assertThat(publicado().estado()).isEqualTo(EstadoInstrumento.SUSPENDIDO);
    }

    @Test
    void suspenderUnoYaSuspendidoNoPublicaNada() {
        existente(EstadoInstrumento.SUSPENDIDO);

        servicio.suspender("ANDX");

        verifyNoInteractions(publicador);
    }

    @Test
    void reactivarPublicaElInstrumentoActivo() {
        Instrumento andx = existente(EstadoInstrumento.SUSPENDIDO);

        servicio.reactivar("ANDX");

        assertThat(andx.getEstado()).isEqualTo(EstadoInstrumento.ACTIVO);
        assertThat(publicado().estado()).isEqualTo(EstadoInstrumento.ACTIVO);
    }

    @Test
    void cambiarUnInstrumentoQueNoExisteResponde404SinPublicar() {
        assertThatThrownBy(() -> servicio.suspender("nada"))
                .isInstanceOf(InstrumentoNoEncontradoException.class)
                .hasMessage("El instrumento NADA no existe.");
        verifyNoInteractions(publicador);
    }

    /** ANDX como si viniera de la base, ya bloqueado para cambiarlo. */
    private Instrumento existente(EstadoInstrumento estado) {
        Instrumento andx = new Instrumento("ANDX", "Andex Energía", "Energía", 5_000, 10, 1_000_000, AYER);
        if (estado == EstadoInstrumento.SUSPENDIDO) {
            andx.suspender(AYER);
        }
        when(repositorio.bloquear("ANDX")).thenReturn(Optional.of(andx));
        return andx;
    }

    private InstrumentoActualizado publicado() {
        ArgumentCaptor<InstrumentoActualizado> evento = ArgumentCaptor.forClass(InstrumentoActualizado.class);
        verify(publicador).publicar(evento.capture());
        return evento.getValue();
    }
}
