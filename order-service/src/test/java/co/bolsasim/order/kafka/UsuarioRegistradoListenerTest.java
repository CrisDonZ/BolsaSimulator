package co.bolsasim.order.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.bolsasim.eventos.UsuarioRegistrado;
import co.bolsasim.order.usuario.RepositorioUsuarios;
import co.bolsasim.order.usuario.Usuario;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Prueba unitaria del listener: se llama al método directamente, sin Kafka ni base de datos. */
class UsuarioRegistradoListenerTest {

    private final RepositorioUsuarios repositorio = mock(RepositorioUsuarios.class);
    private final UsuarioRegistradoListener listener = new UsuarioRegistradoListener(repositorio);
    private final UsuarioRegistrado evento = UsuarioRegistrado.de(UUID.randomUUID(), "ana_inv", "INVERSIONISTA",
            Instant.parse("2026-10-15T10:00:00Z"));

    @Test
    void unUsuarioNuevoSeGuardaConSuIdYSuFechaDeRegistro() {
        listener.recibir(evento);

        ArgumentCaptor<Usuario> guardado = ArgumentCaptor.forClass(Usuario.class);
        verify(repositorio).save(guardado.capture());
        assertThat(guardado.getValue().getId()).isEqualTo(evento.usuarioId());
        assertThat(guardado.getValue().getRegistradoEn()).isEqualTo(evento.registradoEn());
    }

    @Test
    void unEventoRepetidoSeIgnora() {
        when(repositorio.existsById(evento.usuarioId())).thenReturn(true);

        listener.recibir(evento);

        verify(repositorio, never()).save(any());
    }
}
