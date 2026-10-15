package co.bolsasim.auth.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.bolsasim.auth.usuario.EstadoUsuario;
import co.bolsasim.auth.usuario.RepositorioUsuarios;
import co.bolsasim.auth.usuario.Rol;
import co.bolsasim.auth.usuario.Usuario;
import co.bolsasim.auth.web.RegistroUsuarioRequest;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Prueba unitaria: no levanta Spring ni base de datos. El repositorio es un doble de Mockito;
 * el cifrador es el BCrypt real, para comprobar que la contraseña de verdad queda cifrada.
 */
class ServicioUsuariosTest {

    private final RepositorioUsuarios repositorio = mock(RepositorioUsuarios.class);
    private final PasswordEncoder cifrador = new ConfiguracionSeguridad().cifradorContrasenas();
    private final ServicioUsuarios servicio = new ServicioUsuarios(repositorio, cifrador);

    @BeforeEach
    void elRepositorioDevuelveLoQueGuarda() {
        when(repositorio.saveAndFlush(any())).thenAnswer(invocacion -> invocacion.getArgument(0));
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
    void unCorreoYaRegistradoSeRechazaYNoSeGuarda() {
        when(repositorio.existsByCorreo("ana@correo.com")).thenReturn(true);

        assertThatThrownBy(() -> servicio.registrar(registro("Ana@correo.com", "otro_alias")))
                .isInstanceOf(UsuarioDuplicadoException.class)
                .hasMessage("Ese correo ya tiene una cuenta.");
        verify(repositorio, never()).saveAndFlush(any());
    }

    @Test
    void unAliasYaUsadoSeRechazaYNoSeGuarda() {
        when(repositorio.existsByAliasIgnoreCase("ana_inv")).thenReturn(true);

        assertThatThrownBy(() -> servicio.registrar(registro("nueva@correo.com", "ana_inv")))
                .isInstanceOf(UsuarioDuplicadoException.class)
                .hasMessageContaining("ana_inv");
        verify(repositorio, never()).saveAndFlush(any());
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
