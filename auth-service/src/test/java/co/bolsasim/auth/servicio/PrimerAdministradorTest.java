package co.bolsasim.auth.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.bolsasim.auth.usuario.RepositorioUsuarios;
import co.bolsasim.auth.usuario.Rol;
import co.bolsasim.auth.usuario.Usuario;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Prueba unitaria: el repositorio es un doble de Mockito y el cifrador es el BCrypt real. */
class PrimerAdministradorTest {

    private final RepositorioUsuarios repositorio = mock(RepositorioUsuarios.class);
    private final PasswordEncoder cifrador = new ConfiguracionContrasenas().cifradorContrasenas();

    /** Igual que la base de datos: devuelve el usuario guardado. */
    @BeforeEach
    void elRepositorioDevuelveLoQueGuarda() {
        when(repositorio.saveAndFlush(any())).thenAnswer(invocacion -> invocacion.getArgument(0));
    }

    @Test
    void conCorreoYContrasenaCreaUnAdminConLaContrasenaCifrada() {
        arrancar(new PropiedadesAdmin(" Admin@BolsaSim.co ", "clave-del-admin", "admin"));

        ArgumentCaptor<Usuario> guardado = ArgumentCaptor.forClass(Usuario.class);
        verify(repositorio).saveAndFlush(guardado.capture());
        Usuario admin = guardado.getValue();
        assertThat(admin.getRol()).isEqualTo(Rol.ADMIN);
        assertThat(admin.getCorreo()).isEqualTo("admin@bolsasim.co");
        assertThat(admin.getAlias()).isEqualTo("admin");
        assertThat(admin.getPasswordHash()).startsWith("$2a$10$");
        assertThat(cifrador.matches("clave-del-admin", admin.getPasswordHash())).isTrue();
    }

    @Test
    void sinLasVariablesNoCreaANadie() {
        arrancar(new PropiedadesAdmin("", "", "admin"));
        arrancar(new PropiedadesAdmin("admin@bolsasim.co", null, "admin"));

        verify(repositorio, never()).saveAndFlush(any());
    }

    @Test
    void unaCuentaQueYaExisteConEseCorreoNoSeAsciendeAAdmin() {
        // Alguien se registró primero con el correo del administrador, con su propia contraseña
        Usuario intruso = Usuario.nuevoInversionista("Intruso", "intruso", "admin@bolsasim.co", "$2a$10$hash",
                Instant.now());
        when(repositorio.findByCorreo("admin@bolsasim.co")).thenReturn(Optional.of(intruso));

        arrancar(new PropiedadesAdmin("admin@bolsasim.co", "clave-del-admin", "admin"));

        verify(repositorio, never()).saveAndFlush(any());
        assertThat(intruso.getRol()).isEqualTo(Rol.INVERSIONISTA);
    }

    @Test
    void siElAdminYaExisteNoHaceNada() {
        Usuario admin = Usuario.nuevoAdmin("Administrador", "admin", "admin@bolsasim.co", "$2a$10$hash", Instant.now());
        when(repositorio.findByCorreo("admin@bolsasim.co")).thenReturn(Optional.of(admin));

        arrancar(new PropiedadesAdmin("admin@bolsasim.co", "otra-clave-nueva", "admin"));

        verify(repositorio, never()).saveAndFlush(any());
        assertThat(admin.getPasswordHash()).isEqualTo("$2a$10$hash");
    }

    @Test
    void unaContrasenaCortaNoDejaArrancar() {
        assertThatThrownBy(() -> arrancar(new PropiedadesAdmin("admin@bolsasim.co", "corta", "admin")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOLSASIM_ADMIN_CONTRASENA");
        verify(repositorio, never()).saveAndFlush(any());
    }

    @Test
    void unaContrasenaDeMasDe72BytesNoDejaArrancar() {
        // 40 eñes son 40 caracteres pero 80 bytes: con ella el login nunca funcionaría
        assertThatThrownBy(() -> arrancar(new PropiedadesAdmin("admin@bolsasim.co", "ñ".repeat(40), "admin")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("72 bytes");
    }

    @Test
    void unAliasEnUsoNoDejaArrancar() {
        when(repositorio.existsByAliasIgnoreCase("admin")).thenReturn(true);

        assertThatThrownBy(() -> arrancar(new PropiedadesAdmin("admin@bolsasim.co", "clave-del-admin", "admin")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOLSASIM_ADMIN_ALIAS");
        verify(repositorio, never()).saveAndFlush(any());
    }

    @Test
    void unCorreoInvalidoNoDejaArrancar() {
        assertThatThrownBy(() -> arrancar(new PropiedadesAdmin("admin", "clave-del-admin", "admin")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BOLSASIM_ADMIN_CORREO");
    }

    private void arrancar(PropiedadesAdmin propiedades) {
        new PrimerAdministrador(repositorio, cifrador, propiedades).run(null);
    }
}
