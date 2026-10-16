package co.bolsasim.order;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.bolsasim.eventos.Topicos;
import co.bolsasim.eventos.UsuarioRegistrado;
import co.bolsasim.order.usuario.RepositorioUsuarios;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Prueba de integración: order-service completo contra PostgreSQL y Kafka reales en contenedores (RNF-36).
 * Publica un UsuarioRegistrado como lo haría auth-service y comprueba que, cuando llega a la copia
 * local, las órdenes de ese usuario se aceptan y las de un usuario desconocido no.
 *
 * <p>Necesita Docker. Si Docker está cerrado, la prueba se salta en vez de fallar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ValidacionUsuarioIntegracionTest {

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
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private RepositorioUsuarios usuarios;

    @Test
    void cuandoLlegaUsersRegisteredElUsuarioPuedeEnviarOrdenes() throws Exception {
        UUID usuarioId = UUID.randomUUID();
        kafkaTemplate.send(Topicos.USERS_REGISTERED, usuarioId.toString(),
                UsuarioRegistrado.de(usuarioId, "ana_inv", "INVERSIONISTA", Instant.now()));

        // El listener lo guarda de forma asíncrona: esperamos a que aparezca en la copia local
        await().atMost(Duration.ofSeconds(30)).until(() -> usuarios.existsById(usuarioId));

        mvc.perform(post("/api/ordenes").contentType(MediaType.APPLICATION_JSON).content(orden(usuarioId)))
                .andExpect(status().isAccepted());
    }

    @Test
    void unUsuarioQueNuncaSeRegistroRecibe422() throws Exception {
        mvc.perform(post("/api/ordenes").contentType(MediaType.APPLICATION_JSON).content(orden(UUID.randomUUID())))
                .andExpect(status().isUnprocessableContent());
    }

    private static String orden(UUID usuarioId) {
        return """
                {"usuarioId":"%s","simbolo":"ANDX","lado":"COMPRA","tipo":"LIMITE","precioLimite":5000,"cantidad":100}
                """.formatted(usuarioId);
    }
}
