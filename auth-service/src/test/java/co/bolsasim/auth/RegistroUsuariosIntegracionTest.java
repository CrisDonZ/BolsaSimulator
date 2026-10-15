package co.bolsasim.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.bolsasim.auth.usuario.RepositorioUsuarios;
import co.bolsasim.auth.usuario.Usuario;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Prueba de integración: el servicio completo contra un PostgreSQL real en un contenedor (RNF-36).
 * Comprueba que Flyway crea la tabla, que coincide con la entidad y que la base hace cumplir sus reglas.
 *
 * <p>Necesita Docker. Si Docker está cerrado, la prueba se salta en vez de fallar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class RegistroUsuariosIntegracionTest {

    // @ServiceConnection le pasa a Spring la URL, el usuario y la clave del contenedor
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RepositorioUsuarios repositorio;

    @Test
    void unUsuarioRegistradoQuedaGuardadoYSePuedeConsultarPorSuId() throws Exception {
        String respuesta = mvc.perform(post("/api/usuarios").contentType(MediaType.APPLICATION_JSON).content("""
                        {"nombre":"Beto Ruiz","alias":"beto","correo":"beto@correo.com",
                         "contrasena":"secreta123","aceptaPoliticaDatos":true}
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = respuesta.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        mvc.perform(get("/api/usuarios/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alias").value("beto"));
        assertThat(repositorio.existsByCorreo("beto@correo.com")).isTrue();
    }

    @Test
    void registrarDosVecesElMismoCorreoResponde409() throws Exception {
        String registro = """
                {"nombre":"Cami Díaz","alias":"%s","correo":"cami@correo.com",
                 "contrasena":"secreta123","aceptaPoliticaDatos":true}
                """;
        mvc.perform(post("/api/usuarios").contentType(MediaType.APPLICATION_JSON).content(registro.formatted("cami")))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/usuarios").contentType(MediaType.APPLICATION_JSON)
                        .content(registro.formatted("cami_2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Ese correo ya tiene una cuenta."));
    }

    @Test
    void laBaseRechazaUnCorreoRepetidoAunqueSeSalteElServicio() {
        repositorio.saveAndFlush(Usuario.nuevoInversionista("Dani", "dani", "dani@correo.com", "hash", Instant.now()));
        Usuario repetido = Usuario.nuevoInversionista("Dani 2", "dani_2", "dani@correo.com", "hash", Instant.now());

        assertThatThrownBy(() -> repositorio.saveAndFlush(repetido))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
