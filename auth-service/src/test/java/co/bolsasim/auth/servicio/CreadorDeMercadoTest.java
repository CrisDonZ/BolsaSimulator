package co.bolsasim.auth.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.bolsasim.auth.usuario.RepositorioUsuarios;
import co.bolsasim.auth.usuario.Rol;
import co.bolsasim.auth.usuario.Usuario;
import co.bolsasim.eventos.UsuarioRegistrado;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Prueba unitaria: el repositorio y el publicador son dobles de Mockito y el cifrador es el BCrypt real.
 */
class CreadorDeMercadoTest {

    private static final String CORREO = "creador@bolsasim.co";

    private final RepositorioUsuarios repositorio = mock(RepositorioUsuarios.class);
    private final PublicadorUsuarios publicador = mock(PublicadorUsuarios.class);
    private final PasswordEncoder cifrador = new ConfiguracionContrasenas().cifradorContrasenas();

    /** Igual que la base de datos: devuelve el usuario guardado, con su id. */
    @BeforeEach
    void elRepositorioDevuelveLoQueGuarda() {
        when(repositorio.saveAndFlush(any())).thenAnswer(invocacion -> {
            Usuario usuario = invocacion.getArgument(0);
            ReflectionTestUtils.setField(usuario, "id", UUID.randomUUID());
            return usuario;
        });
    }

    @Test
    void conCorreoYContrasenaCreaUnBotYPublicaQueEsElCreadorDeMercado() {
        arrancar(new PropiedadesCreadorMercado(" Creador@BolsaSim.co ", "clave-del-bot", "creador_mercado"));

        ArgumentCaptor<Usuario> guardado = ArgumentCaptor.forClass(Usuario.class);
        verify(repositorio).saveAndFlush(guardado.capture());
        Usuario bot = guardado.getValue();
        assertThat(bot.getRol()).isEqualTo(Rol.BOT);
        assertThat(bot.getCorreo()).isEqualTo(CORREO);
        assertThat(cifrador.matches("clave-del-bot", bot.getPasswordHash())).isTrue();

        UsuarioRegistrado evento = publicado();
        assertThat(evento.usuarioId()).isEqualTo(bot.getId());
        assertThat(evento.rol()).isEqualTo("BOT");
        assertThat(evento.alias()).isEqualTo("creador_mercado");
        assertThat(evento.creadorDeMercado()).isTrue();
    }

    @Test
    void sinLasVariablesNoCreaANadieNiPublicaNada() {
        arrancar(new PropiedadesCreadorMercado("", "", "creador_mercado"));
        arrancar(new PropiedadesCreadorMercado(CORREO, null, "creador_mercado"));

        verify(repositorio, never()).saveAndFlush(any());
        verifyNoInteractions(publicador);
    }

    @Test
    void siYaExisteLoVuelveAPublicarConElMismoEventoSinCrearOtro() {
        Usuario existente = Usuario.nuevoBot("Creador de mercado", "creador_mercado", CORREO, "$2a$10$hash",
                Instant.parse("2026-10-21T12:00:00Z"));
        ReflectionTestUtils.setField(existente, "id", UUID.randomUUID());
        when(repositorio.findByCorreo(CORREO)).thenReturn(Optional.of(existente));

        arrancar(new PropiedadesCreadorMercado(CORREO, "clave-del-bot", "creador_mercado"));

        verify(repositorio, never()).saveAndFlush(any());
        UsuarioRegistrado evento = publicado();
        // El mismo eventoId en cada arranque: quien ya lo procesó lo reconoce como repetido
        assertThat(evento.eventoId()).isEqualTo(
                UsuarioRegistrado.delCreadorDeMercado(existente.getId(), "creador_mercado", Instant.now()).eventoId());
        assertThat(evento.creadorDeMercado()).isTrue();
    }

    @Test
    void unaCuentaQueYaExisteYNoEsBotNoSeAsciendeNiSePublica() {
        // Alguien se registró primero con el correo del creador, con su propia contraseña
        Usuario intruso = Usuario.nuevoInversionista("Intruso", "intruso", CORREO, "$2a$10$hash", Instant.now());
        when(repositorio.findByCorreo(CORREO)).thenReturn(Optional.of(intruso));

        arrancar(new PropiedadesCreadorMercado(CORREO, "clave-del-bot", "creador_mercado"));

        verify(repositorio, never()).saveAndFlush(any());
        verifyNoInteractions(publicador);
        assertThat(intruso.getRol()).isEqualTo(Rol.INVERSIONISTA);
    }

    @Test
    void unaContrasenaCortaOUnAliasInvalidoNoDejanArrancar() {
        assertThatThrownBy(() -> arrancar(new PropiedadesCreadorMercado(CORREO, "corta", "creador_mercado")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOLSASIM_CREADOR_MERCADO_CONTRASENA");
        assertThatThrownBy(() -> arrancar(new PropiedadesCreadorMercado(CORREO, "clave-del-bot", "no vale!")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOLSASIM_CREADOR_MERCADO_ALIAS");
        assertThatThrownBy(() -> arrancar(new PropiedadesCreadorMercado("sin-arroba", "clave-del-bot", "bot")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOLSASIM_CREADOR_MERCADO_CORREO");
        verifyNoInteractions(publicador);
    }

    @Test
    void unAliasQueYaUsaOtraCuentaNoDejaArrancar() {
        when(repositorio.existsByAliasIgnoreCase("creador_mercado")).thenReturn(true);

        assertThatThrownBy(() -> arrancar(new PropiedadesCreadorMercado(CORREO, "clave-del-bot", "creador_mercado")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ya está en uso");
    }

    private void arrancar(PropiedadesCreadorMercado propiedades) {
        new CreadorDeMercado(repositorio, cifrador, publicador, propiedades).run(null);
    }

    private UsuarioRegistrado publicado() {
        ArgumentCaptor<UsuarioRegistrado> evento = ArgumentCaptor.forClass(UsuarioRegistrado.class);
        verify(publicador).publicar(evento.capture());
        return evento.getValue();
    }
}
