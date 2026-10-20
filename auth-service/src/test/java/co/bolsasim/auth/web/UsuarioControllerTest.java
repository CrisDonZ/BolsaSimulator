package co.bolsasim.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.bolsasim.auth.TokensDePrueba;
import co.bolsasim.auth.seguridad.ConfiguracionSeguridad;
import co.bolsasim.auth.seguridad.RespuestaNoAutorizado;
import co.bolsasim.auth.servicio.ConfiguracionJwt;
import co.bolsasim.auth.servicio.ServicioUsuarios;
import co.bolsasim.auth.servicio.UsuarioDuplicadoException;
import co.bolsasim.auth.servicio.UsuarioNoEncontradoException;
import co.bolsasim.auth.usuario.Usuario;
import com.nimbusds.jose.jwk.RSAKey;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Prueba del controlador con la seguridad real: levanta la capa web (rutas, validaciones, JSON y manejo
 * de errores), la cadena de seguridad y la clave de firma (un par temporal). Los tokens se firman con esa
 * clave, como los de auth-service. El servicio es un doble de Mockito, así no hace falta base de datos.
 */
@WebMvcTest(UsuarioController.class)
@Import({ConfiguracionJwt.class, ConfiguracionSeguridad.class, RespuestaNoAutorizado.class})
class UsuarioControllerTest {

    private static final String REGISTRO_VALIDO = """
            {"nombre":"Ana Pérez","alias":"ana_inv","correo":"ana@correo.com",
             "contrasena":"secreta123","aceptaPoliticaDatos":true}
            """;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RSAKey claveFirma;

    @MockitoBean
    private ServicioUsuarios servicio;

    private final Usuario ana = usuarioGuardado("ana_inv", "ana@correo.com");
    private final String anaId = ana.getId().toString();

    @Test
    void registroValidoResponde201ConLaUbicacionYSinLaContrasena() throws Exception {
        when(servicio.registrar(any())).thenReturn(ana);

        mvc.perform(post("/api/usuarios").contentType(MediaType.APPLICATION_JSON).content(REGISTRO_VALIDO))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/usuarios/" + ana.getId()))
                .andExpect(jsonPath("$.id").value(anaId))
                .andExpect(jsonPath("$.alias").value("ana_inv"))
                .andExpect(jsonPath("$.rol").value("INVERSIONISTA"))
                .andExpect(jsonPath("$.estado").value("ACTIVO"))
                .andExpect(jsonPath("$.contrasena").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void registrarseConUnTokenVencidoEnElHeaderIgualFunciona() throws Exception {
        // Registrarse es público: el token se ignora, así uno viejo que quedó en el navegador no estorba
        when(servicio.registrar(any())).thenReturn(ana);

        mvc.perform(post("/api/usuarios").contentType(MediaType.APPLICATION_JSON).content(REGISTRO_VALIDO)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TokensDePrueba.vencido(claveFirma, anaId)))
                .andExpect(status().isCreated());
    }

    @Test
    void sinAceptarLaPoliticaDeDatosResponde400YNoRegistra() throws Exception {
        String sinPolitica = REGISTRO_VALIDO.replace("\"aceptaPoliticaDatos\":true", "\"aceptaPoliticaDatos\":false");

        mvc.perform(post("/api/usuarios").contentType(MediaType.APPLICATION_JSON).content(sinPolitica))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("aceptaPoliticaDatos")));
        verifyNoInteractions(servicio);
    }

    @Test
    void unRegistroQueNoEsJsonResponde400() throws Exception {
        // MockMvc no pasa por la página de error de Spring: que la seguridad no convierta este 400 en 401
        // con el servicio real lo comprueba scripts/prueba-integracion.sh
        mvc.perform(post("/api/usuarios").contentType(MediaType.APPLICATION_JSON).content("{no es json"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(servicio);
    }

    @Test
    void datosInvalidosResponden400DiciendoQueCampoCorregir() throws Exception {
        String invalido = """
                {"nombre":"","alias":"a!","correo":"no-es-correo","contrasena":"corta","aceptaPoliticaDatos":true}
                """;

        mvc.perform(post("/api/usuarios").contentType(MediaType.APPLICATION_JSON).content(invalido))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Datos incompletos o inválidos"))
                .andExpect(jsonPath("$.detail").value(allOf(
                        containsString("nombre"),
                        containsString("alias"),
                        containsString("correo"),
                        containsString("contrasena: debe tener entre 8 y 72 caracteres"))));
        verifyNoInteractions(servicio);
    }

    @Test
    void correoYaRegistradoResponde409ConElMensajeDeLaHistoria() throws Exception {
        when(servicio.registrar(any())).thenThrow(new UsuarioDuplicadoException("Ese correo ya tiene una cuenta."));

        mvc.perform(post("/api/usuarios").contentType(MediaType.APPLICATION_JSON).content(REGISTRO_VALIDO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Ese correo ya tiene una cuenta."));
    }

    @Test
    void conSuTokenUnUsuarioConsultaSuPropioPerfil() throws Exception {
        when(servicio.buscar(ana.getId())).thenReturn(ana);

        consultar(anaId, TokensDePrueba.valido(claveFirma, anaId, "INVERSIONISTA"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correo").value("ana@correo.com"));
    }

    @Test
    void sinTokenResponde401YNoConsultaLaBase() throws Exception {
        consultar(anaId, null)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)))
                .andExpect(jsonPath("$.detail").value(startsWith("Falta el token de acceso")));
        verifyNoInteractions(servicio);
    }

    @Test
    void conUnTokenVencidoResponde401() throws Exception {
        rechazado(consultar(anaId, TokensDePrueba.vencido(claveFirma, anaId)));
    }

    @Test
    void conLaFirmaAlteradaResponde401() throws Exception {
        // Ana cambia el "sub" de su token por el id de otro usuario para ver su perfil
        String otro = UUID.randomUUID().toString();
        String alterado = TokensDePrueba.alterado(TokensDePrueba.valido(claveFirma, anaId, "INVERSIONISTA"), anaId, otro);

        rechazado(consultar(otro, alterado));
    }

    @Test
    void conUnTokenFirmadoConOtraClaveResponde401() throws Exception {
        rechazado(consultar(anaId, TokensDePrueba.firmadoConOtraClave(claveFirma, anaId)));
    }

    @Test
    void conUnTokenDeOtroEmisorResponde401() throws Exception {
        String deOtroEmisor = TokensDePrueba.firmar(claveFirma, "otro-sistema", anaId, "INVERSIONISTA",
                Instant.now(), Instant.now().plusSeconds(900));

        rechazado(consultar(anaId, deOtroEmisor));
    }

    @Test
    void elPerfilDeOtroUsuarioResponde404IgualQueUnIdQueNoExisteYSinBuscarloEnLaBase() throws Exception {
        Usuario beto = usuarioGuardado("beto_inv", "beto@correo.com");
        when(servicio.buscar(beto.getId())).thenReturn(beto);
        String betoId = beto.getId().toString();
        String inexistente = UUID.randomUUID().toString();
        String tokenDeAna = TokensDePrueba.valido(claveFirma, anaId, "INVERSIONISTA");

        String deBeto = consultar(betoId, tokenDeAna)
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String deNadie = consultar(inexistente, tokenDeAna)
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        // Ana recibe lo mismo en los dos casos, salvo el id que ella misma pidió: no sabe que Beto existe
        assertThat(deBeto).isEqualTo(deNadie.replace(inexistente, betoId))
                .contains("\"title\":\"Usuario no encontrado\"")
                .doesNotContain("beto@correo.com", "beto_inv");
        verifyNoInteractions(servicio);
    }

    @Test
    void unAdminPuedeConsultarCualquierPerfil() throws Exception {
        when(servicio.buscar(ana.getId())).thenReturn(ana);
        String admin = UUID.randomUUID().toString();

        consultar(anaId, TokensDePrueba.valido(claveFirma, admin, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alias").value("ana_inv"));
    }

    @Test
    void unBotSoloPuedeConsultarSuPropioPerfil() throws Exception {
        consultar(anaId, TokensDePrueba.valido(claveFirma, UUID.randomUUID().toString(), "BOT"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(servicio);
    }

    @Test
    void consultarSuPerfilSiYaNoExisteResponde404() throws Exception {
        when(servicio.buscar(ana.getId())).thenThrow(new UsuarioNoEncontradoException(ana.getId()));

        consultar(anaId, TokensDePrueba.valido(claveFirma, anaId, "INVERSIONISTA"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString(anaId)));
    }

    private ResultActions consultar(String id, String token) throws Exception {
        var peticion = get("/api/usuarios/{id}", id);
        if (token != null) {
            peticion.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(peticion);
    }

    private void rechazado(ResultActions respuesta) throws Exception {
        respuesta.andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""))
                .andExpect(jsonPath("$.detail").value(startsWith("El token de acceso no es válido o ya venció")));
        verifyNoInteractions(servicio);
    }

    /** Un usuario como si viniera de la base: JPA es quien le pone el id. */
    private static Usuario usuarioGuardado(String alias, String correo) {
        Usuario usuario = Usuario.nuevoInversionista("Ana Pérez", alias, correo, "$2a$10$hash", Instant.now());
        ReflectionTestUtils.setField(usuario, "id", UUID.randomUUID());
        return usuario;
    }
}
