package co.bolsasim.auth.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.bolsasim.auth.sesion.RepositorioTokensRefresco;
import co.bolsasim.auth.sesion.TokenRefresco;
import co.bolsasim.auth.usuario.EstadoUsuario;
import co.bolsasim.auth.usuario.RepositorioUsuarios;
import co.bolsasim.auth.usuario.Usuario;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Prueba unitaria: no levanta Spring ni base de datos. Los repositorios y el emisor de JWT son dobles
 * de Mockito; el cifrador es el BCrypt real, para comparar contraseñas de verdad.
 */
class ServicioSesionesTest {

    private static final PropiedadesJwt PROPIEDADES =
            new PropiedadesJwt("bolsasim-auth", Duration.ofMinutes(15), Duration.ofDays(7), null);
    private static final TokenAcceso TOKEN_ACCESO = new TokenAcceso("jwt-firmado", Instant.now().plusSeconds(900));

    private final RepositorioUsuarios usuarios = mock(RepositorioUsuarios.class);
    private final RepositorioTokensRefresco tokens = mock(RepositorioTokensRefresco.class);
    private final PasswordEncoder cifrador = new ConfiguracionContrasenas().cifradorContrasenas();
    private final EmisorTokens emisor = mock(EmisorTokens.class);
    private final ServicioSesiones servicio = new ServicioSesiones(usuarios, tokens, cifrador, emisor, PROPIEDADES);

    private final Usuario ana = usuario("ana@correo.com", "secreta123");

    @BeforeEach
    void elEmisorFirmaTokens() {
        when(emisor.emitir(any())).thenReturn(TOKEN_ACCESO);
        when(usuarios.findByCorreo("ana@correo.com")).thenReturn(Optional.of(ana));
    }

    @Test
    void loginCorrectoEntregaElTokenDeAccesoYUnTokenDeRefrescoDe7Dias() {
        Sesion sesion = servicio.iniciar("ana@correo.com", "secreta123");

        assertThat(sesion.acceso()).isEqualTo(TOKEN_ACCESO);
        assertThat(sesion.tokenRefresco()).hasSizeGreaterThanOrEqualTo(43);
        assertThat(sesion.refrescoExpiraEn())
                .isBetween(Instant.now().plus(Duration.ofDays(7)).minusSeconds(5), Instant.now().plus(Duration.ofDays(7)));
        verify(emisor).emitir(ana);
    }

    @Test
    void enLaBaseSeGuardaElHashDelTokenDeRefrescoNuncaElToken() {
        Sesion sesion = servicio.iniciar("ana@correo.com", "secreta123");

        ArgumentCaptor<TokenRefresco> guardado = ArgumentCaptor.forClass(TokenRefresco.class);
        verify(tokens).save(guardado.capture());
        assertThat(guardado.getValue().getTokenHash())
                .isNotEqualTo(sesion.tokenRefresco())
                .isEqualTo(ServicioSesiones.hash(sesion.tokenRefresco()));
        assertThat(guardado.getValue().getUsuario()).isEqualTo(ana);
        assertThat(guardado.getValue().isRevocado()).isFalse();
    }

    @Test
    void elCorreoSeBuscaEnMinusculasYSinEspacios() {
        Sesion sesion = servicio.iniciar("  Ana@Correo.COM ", "secreta123");

        assertThat(sesion.acceso()).isEqualTo(TOKEN_ACCESO);
    }

    @Test
    void contrasenaIncorrectaSeRechazaSinDecirQueFallo() {
        assertThatThrownBy(() -> servicio.iniciar("ana@correo.com", "otra-clave"))
                .isInstanceOf(CredencialesInvalidasException.class)
                .hasMessage("Correo o contraseña incorrectos");
        verifyNoInteractions(emisor);
        verify(tokens, never()).save(any());
    }

    @Test
    void correoNoRegistradoSeRechazaConElMismoMensaje() {
        when(usuarios.findByCorreo("nadie@correo.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.iniciar("nadie@correo.com", "secreta123"))
                .isInstanceOf(CredencialesInvalidasException.class)
                .hasMessage("Correo o contraseña incorrectos");
        verifyNoInteractions(emisor);
        verify(tokens, never()).save(any());
    }

    @Test
    void unaContrasenaDeMasDe72BytesSeRechazaSinFallar() {
        assertThatThrownBy(() -> servicio.iniciar("ana@correo.com", "ñ".repeat(40)))
                .isInstanceOf(CredencialesInvalidasException.class);
    }

    @Test
    void unUsuarioBloqueadoNoPuedeIniciarSesion() {
        ReflectionTestUtils.setField(ana, "estado", EstadoUsuario.BLOQUEADO);

        assertThatThrownBy(() -> servicio.iniciar("ana@correo.com", "secreta123"))
                .isInstanceOf(CredencialesInvalidasException.class);
        verify(tokens, never()).save(any());
    }

    @Test
    void refrescoValidoEntregaUnTokenDeAccesoNuevo() {
        TokenRefresco token = guardarToken("token-de-ana", Instant.now().plus(Duration.ofDays(7)));

        TokenAcceso renovado = servicio.renovar("token-de-ana");

        assertThat(renovado).isEqualTo(TOKEN_ACCESO);
        verify(emisor).emitir(token.getUsuario());
    }

    @Test
    void refrescoDespuesDeCerrarSesionSeRechaza() {
        guardarToken("token-de-ana", Instant.now().plus(Duration.ofDays(7)));

        servicio.cerrar(ana.getId().toString(), "token-de-ana");

        assertThatThrownBy(() -> servicio.renovar("token-de-ana"))
                .isInstanceOf(TokenRefrescoInvalidoException.class);
        verifyNoInteractions(emisor);
    }

    @Test
    void refrescoVencidoSeRechaza() {
        guardarToken("token-de-ana", Instant.now().minusSeconds(1));

        assertThatThrownBy(() -> servicio.renovar("token-de-ana"))
                .isInstanceOf(TokenRefrescoInvalidoException.class);
    }

    @Test
    void refrescoDesconocidoSeRechaza() {
        assertThatThrownBy(() -> servicio.renovar("token-inventado"))
                .isInstanceOf(TokenRefrescoInvalidoException.class);
    }

    @Test
    void refrescoDeUnUsuarioQueFueBloqueadoSeRechaza() {
        guardarToken("token-de-ana", Instant.now().plus(Duration.ofDays(7)));
        ReflectionTestUtils.setField(ana, "estado", EstadoUsuario.BLOQUEADO);

        assertThatThrownBy(() -> servicio.renovar("token-de-ana"))
                .isInstanceOf(TokenRefrescoInvalidoException.class);
    }

    @Test
    void cerrarSesionRevocaElTokenDeRefresco() {
        TokenRefresco token = guardarToken("token-de-ana", Instant.now().plus(Duration.ofDays(7)));

        servicio.cerrar(ana.getId().toString(), "token-de-ana");

        assertThat(token.isRevocado()).isTrue();
    }

    @Test
    void nadiePuedeCerrarLaSesionDeOtroUsuario() {
        TokenRefresco token = guardarToken("token-de-ana", Instant.now().plus(Duration.ofDays(7)));

        servicio.cerrar(UUID.randomUUID().toString(), "token-de-ana");

        assertThat(token.isRevocado()).isFalse();
        assertThat(servicio.renovar("token-de-ana")).isEqualTo(TOKEN_ACCESO);
    }

    @Test
    void cerrarSesionConUnTokenDesconocidoNoFalla() {
        servicio.cerrar(ana.getId().toString(), "token-inventado");
    }

    /** Lo que la base devolvería al buscar el token por su hash. */
    private TokenRefresco guardarToken(String valor, Instant expiraEn) {
        TokenRefresco token = new TokenRefresco(ana, ServicioSesiones.hash(valor), expiraEn);
        when(tokens.findByTokenHash(ServicioSesiones.hash(valor))).thenReturn(Optional.of(token));
        return token;
    }

    private Usuario usuario(String correo, String contrasena) {
        Usuario usuario = Usuario.nuevoInversionista("Ana Pérez", "ana_inv", correo, cifrador.encode(contrasena),
                Instant.now());
        ReflectionTestUtils.setField(usuario, "id", UUID.randomUUID());
        return usuario;
    }
}
