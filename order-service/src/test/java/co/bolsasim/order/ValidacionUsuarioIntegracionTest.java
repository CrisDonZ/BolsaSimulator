package co.bolsasim.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.bolsasim.eventos.Topicos;
import co.bolsasim.eventos.UsuarioRegistrado;
import co.bolsasim.order.usuario.RepositorioUsuarios;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Prueba de integración: order-service completo contra PostgreSQL y Kafka reales en contenedores (RNF-36).
 * Publica un UsuarioRegistrado como lo haría auth-service y comprueba que, cuando llega a la copia
 * local, ese usuario puede enviar órdenes con su token y que el comando que llega a orders.commands
 * va a su nombre. Los tokens se firman con la clave de {@link TokensDePrueba}.
 *
 * <p>Necesita Docker. Si Docker está cerrado, la prueba se salta en vez de fallar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ValidacionUsuarioIntegracionTest {

    private static final String ORDEN = """
            {"simbolo":"ANDX","lado":"COMPRA","tipo":"LIMITE","precioLimite":5000,"cantidad":100}""";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    // La misma imagen de docker-compose.yml. Aquí Kafka sí crea los tópicos automáticamente.
    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.2.1");

    @DynamicPropertySource
    static void jwksDePrueba(DynamicPropertyRegistry propiedades) {
        TokensDePrueba.configurar(propiedades);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private RepositorioUsuarios usuarios;

    @Test
    void cuandoLlegaUsersRegisteredElUsuarioPuedeEnviarOrdenesConSuToken() throws Exception {
        UUID usuarioId = UUID.randomUUID();
        kafkaTemplate.send(Topicos.USERS_REGISTERED, usuarioId.toString(),
                UsuarioRegistrado.de(usuarioId, "ana_inv", "INVERSIONISTA", Instant.now()));

        // El listener lo guarda de forma asíncrona: esperamos a que aparezca en la copia local
        await().atMost(Duration.ofSeconds(30)).until(() -> usuarios.existsById(usuarioId));

        mvc.perform(post("/api/ordenes").contentType(MediaType.APPLICATION_JSON).content(ORDEN)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TokensDePrueba.valido(usuarioId.toString())))
                .andExpect(status().isAccepted());

        // El comando que lee el motor lleva el "sub" del token
        assertThat(comandosPublicados()).anySatisfy(comando ->
                assertThat(comando).contains("\"usuarioId\":\"" + usuarioId + "\""));
    }

    @Test
    void sinTokenResponde401() throws Exception {
        mvc.perform(post("/api/ordenes").contentType(MediaType.APPLICATION_JSON).content(ORDEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unUsuarioQueNuncaSeRegistroRecibe422AunqueTengaUnTokenValido() throws Exception {
        mvc.perform(post("/api/ordenes").contentType(MediaType.APPLICATION_JSON).content(ORDEN)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TokensDePrueba.valido(UUID.randomUUID().toString())))
                .andExpect(status().isUnprocessableContent());
    }

    /** Lee orders.commands desde el principio, como texto, hasta que llega algo o pasan 30 segundos. */
    private static List<String> comandosPublicados() {
        List<String> comandos = new CopyOnWriteArrayList<>();
        Map<String, Object> configuracion = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "prueba-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumidor = new KafkaConsumer<>(configuracion, new StringDeserializer(), new StringDeserializer())) {
            consumidor.subscribe(List.of(Topicos.ORDERS_COMMANDS));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumidor.poll(Duration.ofMillis(500)).forEach(mensaje -> comandos.add(mensaje.value()));
                return !comandos.isEmpty();
            });
        }
        return comandos;
    }
}
