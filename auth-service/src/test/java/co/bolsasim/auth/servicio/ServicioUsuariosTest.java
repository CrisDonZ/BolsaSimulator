package co.bolsasim.auth.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.bolsasim.auth.usuario.EstadoUsuario;
import co.bolsasim.auth.usuario.RepositorioUsuarios;
import co.bolsasim.auth.usuario.Rol;
import co.bolsasim.auth.usuario.Usuario;
import co.bolsasim.auth.web.RegistroUsuarioRequest;
import co.bolsasim.eventos.UsuarioRegistrado;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Prueba unitaria: no levanta Spring, base de datos ni Kafka. El repositorio y el publicador son
 * dobles de Mockito; el cifrador es el BCrypt real, para comprobar que la contraseña de verdad queda cifrada.
 */
class ServicioUsuariosTest {

    private final RepositorioUsuarios repositorio = mock(RepositorioUsuarios.class);
    private final PasswordEncoder cifrador = new ConfiguracionContrasenas().cifradorContrasenas();
    private final PublicadorUsuarios publicador = mock(PublicadorUsuarios.class);
    private final ServicioUsuarios servicio = new ServicioUsuarios(repositorio, cifrador, publicador);

    /** Igual que la base de datos: devuelve el usuario guardado, ya con su id. */
    @BeforeEach
    void elRepositorioGuardaYAsignaElId() {
        when(repositorio.saveAndFlush(any())).thenAnswer(invocacion -> {
            Usuario usuario = invocacion.getArgument(0);
            ReflectionTestUtils.setField(usuario, "id", UUID.randomUUID());
            return usuario;
        });
    }

    @Test
    void alRegistrarSePublicaUsuarioRegistradoConElIdDelUsuarioGuardado() {
        Usuario usuario = servicio.registrar(registro("ana@correo.com", "ana_inv"));

        ArgumentCaptor<UsuarioRegistrado> evento = ArgumentCaptor.forClass(UsuarioRegistrado.class);
        verify(publicador).publicar(evento.capture());
        assertThat(evento.getValue().usuarioId()).isEqualTo(usuario.getId());
        assertThat(evento.getValue().alias()).isEqualTo("ana_inv");
        assertThat(evento.getValue().rol()).isEqualTo("INVERSIONISTA");
        assertThat(evento.getValue().registradoEn()).isEqualTo(usuario.getCreadoEn());
        assertThat(evento.getValue().eventoId()).isNotNull();
    }

    @Test
    void siLaBaseRechazaElUsuarioNoSePublicaNada() {
        // doThrow y no when(...): when llamaría a la respuesta del @BeforeEach con un argumento nulo
        doThrow(new DataIntegrityViolationException("correo repetido")).when(repositorio).saveAndFlush(any());

        assertThatThrownBy(() -> servicio.registrar(registro("ana@correo.com", "ana_inv")))
                .isInstanceOf(DataIntegrityViolationException.class);
        verifyNoInteractions(publicador);
    }

    @Test
    void laContrasenaSeGuardaCifradaConBCrypt() {
        Usuario usuario = servicio.registrar(registro("ana@correo.com", "ana_inv"));

        assertThat(usuario.getPasswordHash())
                .isNotEqualTo("secreta123")
                .startsWith("$2a$10$");
        assertThat(cifrador.matches("secreta123", usuario.getPasswordHash())).isTrue();
    }

    @Test
    void unUsuarioNuevoEsInversionistaActivoConLaAutorizacionDeDatosRegistrada() {
        Usuario usuario = servicio.registrar(registro("ana@correo.com", "ana_inv"));

        assertThat(usuario.getRol()).isEqualTo(Rol.INVERSIONISTA);
        assertThat(usuario.getEstado()).isEqualTo(EstadoUsuario.ACTIVO);
        assertThat(usuario.getFechaAutorizacionDatos()).isNotNull().isEqualTo(usuario.getCreadoEn());
    }

    @Test
    void elCorreoSeGuardaEnMinusculasYSinEspacios() {
        Usuario usuario = servicio.registrar(registro("  Ana@Correo.COM ", "ana_inv"));

        assertThat(usuario.getCorreo()).isEqualTo("ana@correo.com");
    }

    @Test
    void unCorreoYaRegistradoSeRechazaNoSeGuardaNiSePublica() {
        when(repositorio.existsByCorreo("ana@correo.com")).thenReturn(true);

        assertThatThrownBy(() -> servicio.registrar(registro("Ana@correo.com", "otro_alias")))
                .isInstanceOf(UsuarioDuplicadoException.class)
                .hasMessage("Ese correo ya tiene una cuenta.");
        verify(repositorio, never()).saveAndFlush(any());
        verifyNoInteractions(publicador);
    }

    @Test
    void unAliasYaUsadoSeRechazaNoSeGuardaNiSePublica() {
        when(repositorio.existsByAliasIgnoreCase("ana_inv")).thenReturn(true);

        assertThatThrownBy(() -> servicio.registrar(registro("nueva@correo.com", "ana_inv")))
                .isInstanceOf(UsuarioDuplicadoException.class)
                .hasMessageContaining("ana_inv");
        verify(repositorio, never()).saveAndFlush(any());
        verifyNoInteractions(publicador);
    }

    @Test
    void buscarUnUsuarioQueNoExisteFalla() {
        UUID id = UUID.randomUUID();
        when(repositorio.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.buscar(id))
                .isInstanceOf(UsuarioNoEncontradoException.class)
                .hasMessageContaining(id.toString());
    }

    private static RegistroUsuarioRequest registro(String correo, String alias) {
        return new RegistroUsuarioRequest("Ana Pérez", alias, correo, "secreta123", true);
    }
}
