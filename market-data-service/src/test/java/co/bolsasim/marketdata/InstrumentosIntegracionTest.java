package co.bolsasim.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.bolsasim.eventos.EstadoInstrumento;
import co.bolsasim.eventos.Topicos;
import co.bolsasim.marketdata.instrumento.RepositorioInstrumentos;
import java.time.Duration;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Prueba de integración: market-data-service completo contra PostgreSQL y Kafka reales en contenedores
 * (RNF-36). Comprueba que Flyway crea la tabla con los 10 instrumentos iniciales, que al arrancar se
 * publican en instruments.events y que cada cambio de un ADMIN llega al tópico con el estado completo.
 *
 * <p>Necesita Docker. Si Docker está cerrado, la prueba se salta en vez de fallar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class InstrumentosIntegracionTest {

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
    private RepositorioInstrumentos repositorio;

    @Test
    void losDiezInstrumentosInicialesExistenYSePublicanAlArrancar() throws Exception {
        mvc.perform(get("/api/instrumentos").header(HttpHeaders.AUTHORIZATION, token("INVERSIONISTA")))
                .andExpect(status().isOk())
                // Otra prueba de esta clase crea PRUEB: se comprueban los 10, no el total
                .andExpect(jsonPath("$[*].simbolo").value(hasItems(
                        "ANDX", "CAFE", "BGUA", "TAYR", "QUIM", "FLOR", "COLB", "ARRA", "SALV", "PACF")))
                .andExpect(jsonPath("$[0].simbolo").value("ANDX"))
                .andExpect(jsonPath("$[0].estado").value("ACTIVO"));

        assertThat(eventosDe("ANDX", 1)).first().asString()
                .contains("\"nombre\":\"Andex Energía\"", "\"tick\":10", "\"accionesEmitidas\":1000000");
        assertThat(eventosDe("PACF", 1)).isNotEmpty();
    }

    @Test
    void cadaCambioDeUnAdminLlegaAlTopicoConElEstadoCompleto() throws Exception {
        mvc.perform(post("/api/instrumentos").header(HttpHeaders.AUTHORIZATION, token("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"simbolo":"PRUEB","nombre":"Empresa de Prueba","sector":"Pruebas",
                                 "precioInicial":2500,"tick":5,"accionesEmitidas":750000}"""))
                .andExpect(status().isCreated());
        assertThat(repositorio.findById("PRUEB")).hasValueSatisfying(instrumento ->
                assertThat(instrumento.getEstado()).isEqualTo(EstadoInstrumento.ACTIVO));

        mvc.perform(post("/api/instrumentos/PRUEB/suspender").header(HttpHeaders.AUTHORIZATION, token("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("SUSPENDIDO"));

        List<String> eventos = eventosDe("PRUEB", 2);
        assertThat(eventos.get(0)).contains("\"estado\":\"ACTIVO\"", "\"accionesEmitidas\":750000");
        assertThat(eventos.get(1)).contains("\"estado\":\"SUSPENDIDO\"", "\"nombre\":\"Empresa de Prueba\"",
                "\"tick\":5", "\"accionesEmitidas\":750000");
    }

    @Test
    void crearUnSimboloQueYaExisteResponde409YNoLoCambia() throws Exception {
        mvc.perform(post("/api/instrumentos").header(HttpHeaders.AUTHORIZATION, token("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"simbolo":"cafe","nombre":"Otro Café","sector":"Agro",
                                 "precioInicial":100,"tick":1,"accionesEmitidas":10}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Ese símbolo ya existe"));

        assertThat(repositorio.findById("CAFE")).hasValueSatisfying(instrumento ->
                assertThat(instrumento.getNombre()).isEqualTo("Café Cordillera"));
    }

    @Test
    void unInversionistaNoPuedeSuspenderNada() throws Exception {
        mvc.perform(post("/api/instrumentos/FLOR/suspender").header(HttpHeaders.AUTHORIZATION, token("INVERSIONISTA")))
                .andExpect(status().isForbidden());

        assertThat(repositorio.findById("FLOR")).hasValueSatisfying(instrumento ->
                assertThat(instrumento.getEstado()).isEqualTo(EstadoInstrumento.ACTIVO));
    }

    private static String token(String rol) {
        return "Bearer " + TokensDePrueba.valido(rol);
    }

    /** Lee instruments.events desde el principio, como texto, hasta que haya al menos esa cantidad de mensajes del símbolo. */
    private static List<String> eventosDe(String simbolo, int cantidad) {
        List<String> eventos = new CopyOnWriteArrayList<>();
        Map<String, Object> configuracion = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "prueba-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumidor = new KafkaConsumer<>(configuracion, new StringDeserializer(), new StringDeserializer())) {
            consumidor.subscribe(List.of(Topicos.INSTRUMENTS_EVENTS));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                consumidor.poll(Duration.ofMillis(500)).forEach(mensaje -> {
                    if (simbolo.equals(mensaje.key())) {
                        eventos.add(mensaje.value());
                    }
                });
                return eventos.size() >= cantidad;
            });
        }
        return eventos;
    }
}
