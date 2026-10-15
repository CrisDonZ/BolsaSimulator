package co.bolsasim.auth.web;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.bolsasim.auth.servicio.ServicioUsuarios;
import co.bolsasim.auth.servicio.UsuarioDuplicadoException;
import co.bolsasim.auth.servicio.UsuarioNoEncontradoException;
import co.bolsasim.auth.usuario.Usuario;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Prueba del controlador: levanta solo la capa web (rutas, validaciones, JSON y manejo de errores).
 * El servicio es un doble de Mockito, así no hace falta base de datos.
 */
@WebMvcTest(UsuarioController.class)
class UsuarioControllerTest {

    private static final String REGISTRO_VALIDO = """
            {"nombre":"Ana Pérez","alias":"ana_inv","correo":"ana@correo.com",
             "contrasena":"secreta123","aceptaPoliticaDatos":true}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ServicioUsuarios servicio;

    @Test
    void registroValidoResponde201ConLaUbicacionYSinLaContrasena() throws Exception {
        Usuario ana = usuarioGuardado();
        when(servicio.registrar(any())).thenReturn(ana);

        mvc.perform(post("/api/usuarios").contentType(MediaType.APPLICATION_JSON).content(REGISTRO_VALIDO))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/usuarios/" + ana.getId()))
                .andExpect(jsonPath("$.id").value(ana.getId().toString()))
                .andExpect(jsonPath("$.alias").value("ana_inv"))
                .andExpect(jsonPath("$.rol").value("INVERSIONISTA"))
                .andExpect(jsonPath("$.estado").value("ACTIVO"))
                .andExpect(jsonPath("$.contrasena").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
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
    void consultarUnUsuarioExistenteResponde200() throws Exception {
        Usuario ana = usuarioGuardado();
        when(servicio.buscar(ana.getId())).thenReturn(ana);

        mvc.perform(get("/api/usuarios/{id}", ana.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correo").value("ana@correo.com"));
    }

    @Test
    void consultarUnUsuarioQueNoExisteResponde404() throws Exception {
        UUID id = UUID.randomUUID();
        when(servicio.buscar(id)).thenThrow(new UsuarioNoEncontradoException(id));

        mvc.perform(get("/api/usuarios/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString(id.toString())));
    }

    /** Un usuario como si viniera de la base: JPA es quien le pone el id. */
    private static Usuario usuarioGuardado() {
        Usuario usuario = Usuario.nuevoInversionista("Ana Pérez", "ana_inv", "ana@correo.com", "$2a$10$hash",
                Instant.now());
        ReflectionTestUtils.setField(usuario, "id", UUID.randomUUID());
        return usuario;
    }
}
