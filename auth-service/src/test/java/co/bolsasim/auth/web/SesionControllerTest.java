package co.bolsasim.auth.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.bolsasim.auth.servicio.CredencialesInvalidasException;
import co.bolsasim.auth.servicio.ServicioSesiones;
import co.bolsasim.auth.servicio.Sesion;
import co.bolsasim.auth.servicio.TokenAcceso;
import co.bolsasim.auth.servicio.TokenRefrescoInvalidoException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Prueba del controlador: levanta solo la capa web (rutas, validaciones, JSON y manejo de errores).
 * El servicio es un doble de Mockito, así no hace falta base de datos.
 */
@WebMvcTest(SesionController.class)
class SesionControllerTest {

    private static final String LOGIN = """
            {"correo":"ana@correo.com","contrasena":"secreta123"}
            """;
    private static final String REFRESCO = """
            {"tokenRefresco":"token-de-ana"}
            """;
    private static final TokenAcceso ACCESO = new TokenAcceso("jwt-firmado", Instant.parse("2026-10-16T10:15:00Z"));

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ServicioSesiones servicio;

    @Test
    void loginCorrectoResponde200ConLosDosTokens() throws Exception {
        when(servicio.iniciar("ana@correo.com", "secreta123"))
                .thenReturn(new Sesion(ACCESO, "token-de-ana", Instant.parse("2026-10-23T10:00:00Z")));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenAcceso").value("jwt-firmado"))
                .andExpect(jsonPath("$.tipoToken").value("Bearer"))
                .andExpect(jsonPath("$.accesoExpiraEn").value("2026-10-16T10:15:00Z"))
                .andExpect(jsonPath("$.tokenRefresco").value("token-de-ana"))
                .andExpect(jsonPath("$.refrescoExpiraEn").value("2026-10-23T10:00:00Z"));
    }

    @Test
    void credencialesIncorrectasResponden401SinDecirCualFallo() throws Exception {
        when(servicio.iniciar(anyString(), anyString())).thenThrow(new CredencialesInvalidasException());

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Correo o contraseña incorrectos"))
                .andExpect(jsonPath("$.tokenAcceso").doesNotExist());
    }

    @Test
    void loginSinContrasenaResponde400YNoLlegaAlServicio() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"correo":"ana@correo.com"}
                        """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(servicio);
    }

    @Test
    void refrescoValidoResponde200ConUnTokenDeAccesoNuevo() throws Exception {
        when(servicio.renovar("token-de-ana")).thenReturn(ACCESO);

        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(REFRESCO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenAcceso").value("jwt-firmado"))
                .andExpect(jsonPath("$.tipoToken").value("Bearer"));
    }

    @Test
    void refrescoDeUnaSesionCerradaResponde401() throws Exception {
        when(servicio.renovar(any())).thenThrow(new TokenRefrescoInvalidoException());

        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(REFRESCO))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("La sesión venció o ya se cerró. Inicia sesión de nuevo."));
    }

    @Test
    void cerrarSesionResponde204() throws Exception {
        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON).content(REFRESCO))
                .andExpect(status().isNoContent());
        verify(servicio).cerrar("token-de-ana");
    }
}
