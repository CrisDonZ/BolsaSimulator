package co.bolsasim.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.bolsasim.auth.usuario.RepositorioUsuarios;
import co.bolsasim.auth.usuario.Usuario;
import co.bolsasim.eventos.Topicos;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Prueba de integración: el servicio completo contra PostgreSQL y Kafka reales en contenedores (RNF-36).
 * Comprueba que Flyway crea la tabla, que coincide con la entidad, que la base hace cumplir sus reglas
 * y que el registro llega a Kafka.
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

    // La misma imagen de docker-compose.yml. Aquí Kafka sí crea los tópicos automáticamente.
    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.2.1");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RepositorioUsuarios repositorio;

    @Test
    void unUsuarioRegistradoQuedaGuardadoYConsultaSuPerfilConSuToken() throws Exception {
        String respuesta = mvc.perform(post("/api/usuarios").contentType(MediaType.APPLICATION_JSON).content("""
                        {"nombre":"Beto Ruiz","alias":"beto","correo":"beto@correo.com",
                         "contrasena":"secreta123","aceptaPoliticaDatos":true}
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = respuesta.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        String token = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correo\":\"beto@correo.com\",\"contrasena\":\"secreta123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"tokenAcceso\":\"([^\"]+)\".*", "$1");

        mvc.perform(get("/api/usuarios/{id}", id))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/usuarios/{id}", id).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
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
    void alRegistrarseElUsuarioLlegaAlTopicoUsersRegisteredConSuIdComoClave() throws Exception {
        String respuesta = mvc.perform(post("/api/usuarios").contentType(MediaType.APPLICATION_JSON).content("""
                        {"nombre":"Eva Gil","alias":"eva","correo":"eva@correo.com",
                         "contrasena":"secreta123","aceptaPoliticaDatos":true}
                        """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = respuesta.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        List<ConsumerRecord<String, String>> eventos = leerUsersRegistered(id);

        assertThat(eventos).singleElement().satisfies(evento -> {
            assertThat(evento.value()).contains("\"usuarioId\":\"" + id + "\"", "\"alias\":\"eva\"");
            assertThat(evento.value()).doesNotContain("eva@correo.com", "secreta123");
        });
    }

    @Test
    void laBaseRechazaUnCorreoRepetidoAunqueSeSalteElServicio() {
        repositorio.saveAndFlush(Usuario.nuevoInversionista("Dani", "dani", "dani@correo.com", "hash", Instant.now()));
        Usuario repetido = Usuario.nuevoInversionista("Dani 2", "dani_2", "dani@correo.com", "hash", Instant.now());

        assertThatThrownBy(() -> repositorio.saveAndFlush(repetido))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Lee el tópico desde el principio durante unos segundos y devuelve los mensajes con esa clave. */
    private static List<ConsumerRecord<String, String>> leerUsersRegistered(String clave) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "prueba-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> encontrados = new ArrayList<>();
        try (var consumidor = new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumidor.subscribe(List.of(Topicos.USERS_REGISTERED));
            long hasta = System.currentTimeMillis() + 15_000;
            while (encontrados.isEmpty() && System.currentTimeMillis() < hasta) {
                consumidor.poll(Duration.ofMillis(500)).forEach(registro -> {
                    if (clave.equals(registro.key())) {
                        encontrados.add(registro);
                    }
                });
            }
        }
        return encontrados;
    }
}
