package co.bolsasim.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.bolsasim.auth.sesion.RepositorioTokensRefresco;
import co.bolsasim.auth.usuario.RepositorioUsuarios;
import co.bolsasim.auth.usuario.Usuario;
import com.nimbusds.jose.jwk.JWKSet;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Prueba de integración del inicio de sesión (HU-02) contra un PostgreSQL real en un contenedor (RNF-36):
 * Flyway crea token_refresco, el login entrega tokens de verdad y cerrar sesión invalida el de refresco.
 * No necesita Kafka: el usuario se guarda directo en la base, sin pasar por el registro.
 *
 * <p>Necesita Docker. Si Docker está cerrado, la prueba se salta en vez de fallar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SesionIntegracionTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RepositorioUsuarios usuarios;

    @Autowired
    private RepositorioTokensRefresco tokens;

    @Autowired
    private PasswordEncoder cifrador;

    private Usuario fer;

    @BeforeEach
    void hayUnUsuarioRegistrado() {
        fer = usuarios.findByCorreo("fer@correo.com").orElseGet(() -> usuarios.saveAndFlush(
                Usuario.nuevoInversionista("Fer Gómez", "fer", "fer@correo.com", cifrador.encode("secreta123"),
                        Instant.now())));
    }

    @Test
    void elTokenDeAccesoSeVerificaConLaClavePublicaQuePublicaAuthService() throws Exception {
        String acceso = login("fer@correo.com", "secreta123").replaceAll(".*\"tokenAcceso\":\"([^\"]+)\".*", "$1");

        // Lo mismo que hará el gateway: leer el JWKS y verificar la firma con esa clave pública
        String jwks = mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var clavePublica = JWKSet.parse(jwks).getKeys().getFirst().toRSAKey().toRSAPublicKey();
        Jwt jwt = NimbusJwtDecoder.withPublicKey(clavePublica).build().decode(acceso);

        assertThat(jwt.getSubject()).isEqualTo(fer.getId().toString());
        assertThat(jwt.getClaimAsString("rol")).isEqualTo("INVERSIONISTA");
        assertThat(jwks).doesNotContain("\"d\"");
    }

    @Test
    void conContrasenaIncorrectaOCorreoInexistenteResponde401ConElMismoMensaje() throws Exception {
        for (String login : new String[] {
                "{\"correo\":\"fer@correo.com\",\"contrasena\":\"equivocada\"}",
                "{\"correo\":\"nadie@correo.com\",\"contrasena\":\"secreta123\"}"}) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("Correo o contraseña incorrectos"));
        }
    }

    @Test
    void elRefrescoFuncionaHastaQueSeCierraLaSesion() throws Exception {
        String refresco = login("fer@correo.com", "secreta123")
                .replaceAll(".*\"tokenRefresco\":\"([^\"]+)\".*", "$1");
        String cuerpo = "{\"tokenRefresco\":\"" + refresco + "\"}";

        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenAcceso").isNotEmpty());

        mvc.perform(post("/api/auth/logout").contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void enLaBaseSoloQuedaElHashDelTokenDeRefresco() throws Exception {
        String refresco = login("fer@correo.com", "secreta123")
                .replaceAll(".*\"tokenRefresco\":\"([^\"]+)\".*", "$1");

        assertThat(tokens.findAll())
                .isNotEmpty()
                .noneMatch(token -> token.getTokenHash().equals(refresco));
    }

    private String login(String correo, String contrasena) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correo\":\"" + correo + "\",\"contrasena\":\"" + contrasena + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }
}
