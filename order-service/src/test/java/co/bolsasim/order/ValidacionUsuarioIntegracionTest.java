package co.bolsasim.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.EstadoInstrumento;
import co.bolsasim.eventos.InstrumentoActualizado;
import co.bolsasim.eventos.Topicos;
import co.bolsasim.eventos.UsuarioRegistrado;
import co.bolsasim.order.instrumento.RepositorioInstrumentos;
import co.bolsasim.order.usuario.RepositorioUsuarios;
import co.bolsasim.order.web.OrdenController;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Prueba de integración: order-service completo contra PostgreSQL y Kafka reales en contenedores (RNF-36).
 * Publica un UsuarioRegistrado como lo haría auth-service y comprueba que, cuando llega a la copia
 * local, ese usuario puede enviar órdenes con su token y que el comando que llega a orders.commands
 * va a su nombre. También publica instrumentos como market-data-service y comprueba que uno suspendido
 * rechaza órdenes sin reiniciar nada. Los tokens se firman con la clave de {@link TokensDePrueba}.
 *
 * <p>Necesita Docker. Si Docker está cerrado, la prueba se salta en vez de fallar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
// Cierra el contexto al terminar: si no, el publicador del outbox (cada 100 ms) sigue buscando un PostgreSQL
// que Testcontainers ya apagó y llena el log de errores mientras corren las demás pruebas
@DirtiesContext
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

    @Autowired
    private RepositorioInstrumentos instrumentos;

    @Test
    void cuandoLlegaUsersRegisteredElUsuarioPuedeEnviarOrdenesConSuTokenYConsultarlas() throws Exception {
        publicarInstrumento("ANDX", EstadoInstrumento.ACTIVO);
        UUID usuarioId = registrar();

        String clave = UUID.randomUUID().toString();
        var respuesta = enviarOrden(ORDEN, usuarioId, clave)
                .andExpect(status().isAccepted())
                .andReturn().getResponse();
        String ordenId = respuesta.getContentAsString().replaceAll(".*\"ordenId\":\"([^\"]+)\".*", "$1");
        assertThat(respuesta.getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/ordenes/" + ordenId);

        // El doble clic: la misma solicitud con la misma clave recibe la misma orden y no crea otro comando
        enviarOrden(ORDEN, usuarioId, clave)
                .andExpect(status().isAccepted())
                .andExpect(header().string(OrdenController.IDEMPOTENT_REPLAYED, "true"))
                .andExpect(jsonPath("$.ordenId").value(ordenId));

        // La orden quedó guardada: se puede consultar por id y aparece en la lista, PENDIENTE
        consultar("/api/ordenes/" + ordenId, usuarioId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.simbolo").value("ANDX"))
                .andExpect(jsonPath("$.cantidad").value(100));
        consultar("/api/ordenes", usuarioId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ordenes[0].id").value(ordenId))
                .andExpect(jsonPath("$.siguiente").isEmpty());

        // Otro usuario no la ve: 404 por id y no aparece en su lista
        UUID otro = registrar();
        consultar("/api/ordenes/" + ordenId, otro).andExpect(status().isNotFound());
        consultar("/api/ordenes", otro).andExpect(jsonPath("$.ordenes").isEmpty());

        // El comando sale del outbox una sola vez, igual que antes: misma clave (símbolo), mismo header de tipo
        // con el que el motor lo lee, y a nombre del "sub" del token
        ConsumerRecord<String, String> comando = comandoPublicado(ordenId);
        assertThat(comando.key()).isEqualTo("ANDX");
        assertThat(new String(comando.headers().lastHeader("__TypeId__").value(), StandardCharsets.UTF_8))
                .isEqualTo(ComandoOrden.class.getName());
        assertThat(comando.value()).contains("\"usuarioId\":\"" + usuarioId + "\"", "\"accion\":\"NUEVA\"");
    }

    @Test
    void unInstrumentoSuspendidoPorInstrumentsEventsRechazaOrdenesHastaQueSeReactiva() throws Exception {
        UUID usuarioId = registrar();
        String orden = ORDEN.replace("ANDX", "CAFE");

        // Antes de que market-data-service lo publique, order-service no lo conoce
        enviarOrden(orden, usuarioId).andExpect(status().isUnprocessableContent());

        publicarInstrumento("CAFE", EstadoInstrumento.ACTIVO);
        enviarOrden(orden, usuarioId).andExpect(status().isAccepted());

        publicarInstrumento("CAFE", EstadoInstrumento.SUSPENDIDO);
        enviarOrden(orden, usuarioId)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("El instrumento CAFE está suspendido: no recibe órdenes nuevas."));

        publicarInstrumento("CAFE", EstadoInstrumento.ACTIVO);
        enviarOrden(orden, usuarioId).andExpect(status().isAccepted());
    }

    @Test
    void sinTokenResponde401() throws Exception {
        mvc.perform(post("/api/ordenes").contentType(MediaType.APPLICATION_JSON).content(ORDEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unUsuarioQueNuncaSeRegistroRecibe422AunqueTengaUnTokenValido() throws Exception {
        enviarOrden(ORDEN, UUID.randomUUID()).andExpect(status().isUnprocessableContent());
    }

    /** Publica un UsuarioRegistrado como auth-service y espera a que llegue a la copia local. */
    private UUID registrar() {
        UUID usuarioId = UUID.randomUUID();
        kafkaTemplate.send(Topicos.USERS_REGISTERED, usuarioId.toString(),
                UsuarioRegistrado.de(usuarioId, "ana_inv", "INVERSIONISTA", Instant.now()));
        // El listener lo guarda de forma asíncrona: esperamos a que aparezca en la copia local
        await().atMost(Duration.ofSeconds(30)).until(() -> usuarios.existsById(usuarioId));
        return usuarioId;
    }

    /** Publica el instrumento como market-data-service y espera a que la copia local tenga ese estado. */
    private void publicarInstrumento(String simbolo, EstadoInstrumento estado) {
        kafkaTemplate.send(Topicos.INSTRUMENTS_EVENTS, simbolo, new InstrumentoActualizado(simbolo, "Empresa",
                "Sector", 5_000, 10, 1_000_000, estado, Instant.now()));
        await().atMost(Duration.ofSeconds(30)).until(() -> instrumentos.findById(simbolo)
                .map(instrumento -> instrumento.getEstado() == estado)
                .orElse(false));
    }

    /** Envía la orden con una Idempotency-Key nueva: cada llamada es una orden distinta. */
    private ResultActions enviarOrden(String orden, UUID usuarioId) throws Exception {
        return enviarOrden(orden, usuarioId, UUID.randomUUID().toString());
    }

    private ResultActions enviarOrden(String orden, UUID usuarioId, String clave) throws Exception {
        return mvc.perform(post("/api/ordenes").contentType(MediaType.APPLICATION_JSON).content(orden)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TokensDePrueba.valido(usuarioId.toString()))
                .header(OrdenController.IDEMPOTENCY_KEY, clave));
    }

    private ResultActions consultar(String ruta, UUID usuarioId) throws Exception {
        return mvc.perform(get(ruta)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TokensDePrueba.valido(usuarioId.toString())));
    }

    /**
     * Lee orders.commands desde el principio, como texto, hasta que llega el comando de esa orden o pasan 30
     * segundos. Llega solo cuando el publicador del outbox lo envía.
     */
    private static ConsumerRecord<String, String> comandoPublicado(String ordenId) {
        List<ConsumerRecord<String, String>> comandos = new CopyOnWriteArrayList<>();
        Map<String, Object> configuracion = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "prueba-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumidor = new KafkaConsumer<>(configuracion, new StringDeserializer(), new StringDeserializer())) {
            consumidor.subscribe(List.of(Topicos.ORDERS_COMMANDS));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                leer(consumidor, ordenId, comandos);
                return !comandos.isEmpty();
            });
            // Sigue leyendo 2 segundos más (20 rondas del outbox): un comando repetido llegaría en ese tiempo
            long hasta = System.currentTimeMillis() + 2_000;
            while (System.currentTimeMillis() < hasta) {
                leer(consumidor, ordenId, comandos);
            }
        }
        assertThat(comandos).as("el comando de la orden sale una sola vez").hasSize(1);
        return comandos.getFirst();
    }

    private static void leer(KafkaConsumer<String, String> consumidor, String ordenId,
                             List<ConsumerRecord<String, String>> comandos) {
        consumidor.poll(Duration.ofMillis(200)).forEach(mensaje -> {
            if (mensaje.value().contains("\"ordenId\":\"" + ordenId + "\"")) {
                comandos.add(mensaje);
            }
        });
    }
}
