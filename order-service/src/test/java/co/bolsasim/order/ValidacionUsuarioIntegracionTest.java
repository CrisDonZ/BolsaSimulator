package co.bolsasim.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.EjecucionOrden;
import co.bolsasim.eventos.EstadoInstrumento;
import co.bolsasim.eventos.EstadoOrden;
import co.bolsasim.eventos.EventoOrden;
import co.bolsasim.eventos.InstrumentoActualizado;
import co.bolsasim.eventos.OperacionEjecutada;
import co.bolsasim.eventos.ReservaProcesada;
import co.bolsasim.eventos.ResultadoReserva;
import co.bolsasim.eventos.SolicitudReserva;
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
 * local, ese usuario puede enviar órdenes con su token: cada orden pide su reserva en orders.requested y, cuando
 * portfolio-service la acepta (reservations.events), su comando llega a orders.commands a su nombre; si la rechaza,
 * la orden queda RECHAZADA. También publica instrumentos como market-data-service y operaciones como el motor, y
 * comprueba que uno suspendido rechaza órdenes y que el último precio mueve la banda sin reiniciar nada. Los
 * tokens se firman con la clave de {@link TokensDePrueba}.
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

        // Primero sale la solicitud de reserva, una sola vez: clave = usuario, para portfolio-service
        ConsumerRecord<String, String> solicitud = publicadoUnaVez(Topicos.ORDERS_REQUESTED, ordenId);
        assertThat(solicitud.key()).isEqualTo(usuarioId.toString());
        assertThat(new String(solicitud.headers().lastHeader("__TypeId__").value(), StandardCharsets.UTF_8))
                .isEqualTo(SolicitudReserva.class.getName());
        assertThat(solicitud.value()).contains("\"usuarioId\":\"" + usuarioId + "\"", "\"ultimoPrecio\":5000");

        // portfolio-service acepta la reserva (dos veces, como si Kafka la entregara repetida): el comando sale del
        // outbox una sola vez, igual que antes: misma clave (símbolo), mismo header de tipo con el que el motor lo
        // lee, y a nombre del "sub" del token
        ReservaProcesada aceptada = new ReservaProcesada(UUID.randomUUID(), UUID.fromString(ordenId),
                usuarioId.toString(), ResultadoReserva.ACEPTADA, 500_000, null, Instant.now());
        kafkaTemplate.send(Topicos.RESERVATIONS_EVENTS, usuarioId.toString(), aceptada);
        kafkaTemplate.send(Topicos.RESERVATIONS_EVENTS, usuarioId.toString(), aceptada);
        ConsumerRecord<String, String> comando = publicadoUnaVez(Topicos.ORDERS_COMMANDS, ordenId);
        assertThat(comando.key()).isEqualTo("ANDX");
        assertThat(new String(comando.headers().lastHeader("__TypeId__").value(), StandardCharsets.UTF_8))
                .isEqualTo(ComandoOrden.class.getName());
        assertThat(comando.value()).contains("\"usuarioId\":\"" + usuarioId + "\"", "\"accion\":\"NUEVA\"");
    }

    @Test
    void siPortfolioServiceRechazaLaReservaLaOrdenQuedaRechazadaConElMotivoYNoVaAlMotor() throws Exception {
        publicarInstrumento("ANDX", EstadoInstrumento.ACTIVO);
        UUID usuarioId = registrar();
        String ordenId = enviarOrden(ORDEN, usuarioId)
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"ordenId\":\"([^\"]+)\".*", "$1");

        String motivo = "Saldo insuficiente: tienes 1.000 COP disponibles y la orden necesita 500.000 COP.";
        kafkaTemplate.send(Topicos.RESERVATIONS_EVENTS, usuarioId.toString(), new ReservaProcesada(UUID.randomUUID(),
                UUID.fromString(ordenId), usuarioId.toString(), ResultadoReserva.RECHAZADA, 0, motivo, Instant.now()));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> consultar("/api/ordenes/" + ordenId, usuarioId)
                .andExpect(jsonPath("$.estado").value("RECHAZADA"))
                .andExpect(jsonPath("$.motivo").value(motivo)));
        assertThat(publicados(Topicos.ORDERS_COMMANDS, ordenId)).isEmpty();
    }

    @Test
    void elPrecioDeLaUltimaOperacionMueveLaBandaDeLasOrdenesNuevas() throws Exception {
        publicarInstrumento("QUIM", EstadoInstrumento.ACTIVO);
        UUID usuarioId = registrar();
        String a6000 = ORDEN.replace("ANDX", "QUIM").replace("5000", "6000");

        // Con el precio inicial (5.000) la banda llega hasta 5.500
        enviarOrden(a6000, usuarioId).andExpect(status().isUnprocessableContent());

        // El motor publica una operación a 5.500: ahora la banda va de 4.950 a 6.050
        kafkaTemplate.send(Topicos.TRADES_EXECUTED, "QUIM", new OperacionEjecutada(UUID.randomUUID(), "QUIM", 5_500, 10,
                UUID.randomUUID(), UUID.randomUUID().toString(), UUID.randomUUID(), UUID.randomUUID().toString(),
                Instant.now()));
        await().atMost(Duration.ofSeconds(30)).until(() -> instrumentos.findById("QUIM")
                .map(instrumento -> Long.valueOf(5_500).equals(instrumento.getUltimoPrecio()))
                .orElse(false));

        enviarOrden(a6000, usuarioId).andExpect(status().isAccepted());
        enviarOrden(ORDEN.replace("ANDX", "QUIM").replace("5000", "4900"), usuarioId)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(containsString("entre 4.950 y 6.050")));
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
    void cuandoElMotorPublicaEnOrdersEventsLaOrdenQuedaConSuEstadoYPrecioPromedio() throws Exception {
        publicarInstrumento("ANDX", EstadoInstrumento.ACTIVO);
        UUID usuarioId = registrar();
        String ordenId = enviarOrden(ORDEN, usuarioId)
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"ordenId\":\"([^\"]+)\".*", "$1");
        UUID orden = UUID.fromString(ordenId);

        // Lo que publicaría el motor: entra al libro, se ejecutan 40 a 4.900 y luego 60 a 5.000
        kafkaTemplate.send(Topicos.ORDERS_EVENTS, "ANDX", eventoDelMotor(orden, usuarioId, EstadoOrden.ABIERTA, 0, null));
        kafkaTemplate.send(Topicos.ORDERS_EVENTS, "ANDX", eventoDelMotor(orden, usuarioId, EstadoOrden.PARCIAL, 40,
                new EjecucionOrden(UUID.randomUUID(), 4_900, 40)));
        kafkaTemplate.send(Topicos.ORDERS_EVENTS, "ANDX", eventoDelMotor(orden, usuarioId, EstadoOrden.EJECUTADA, 100,
                new EjecucionOrden(UUID.randomUUID(), 5_000, 60)));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> consultar("/api/ordenes/" + ordenId, usuarioId)
                .andExpect(jsonPath("$.estado").value("EJECUTADA")));
        // (40 × 4900 + 60 × 5000) / 100 = 4960
        consultar("/api/ordenes/" + ordenId, usuarioId)
                .andExpect(jsonPath("$.cantidadEjecutada").value(100))
                .andExpect(jsonPath("$.precioPromedio").value(4960.0));
        consultar("/api/ordenes?estado=EJECUTADA", usuarioId)
                .andExpect(jsonPath("$.ordenes[0].id").value(ordenId));
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

    private static EventoOrden eventoDelMotor(UUID ordenId, UUID usuarioId, EstadoOrden estado, int ejecutada,
                                              EjecucionOrden ejecucion) {
        return new EventoOrden(UUID.randomUUID(), ordenId, usuarioId.toString(), "ANDX", estado, 100, ejecutada,
                ejecucion, null, Instant.now());
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
     * Lee el tópico desde el principio, como texto, hasta que llega el mensaje de esa orden o pasan 30 segundos.
     * Llega solo cuando el publicador del outbox lo envía.
     */
    private static ConsumerRecord<String, String> publicadoUnaVez(String topico, String ordenId) {
        List<ConsumerRecord<String, String>> mensajes = new CopyOnWriteArrayList<>();
        try (var consumidor = consumidor(topico)) {
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                leer(consumidor, ordenId, mensajes);
                return !mensajes.isEmpty();
            });
            // Sigue leyendo 2 segundos más (20 rondas del outbox): un mensaje repetido llegaría en ese tiempo
            long hasta = System.currentTimeMillis() + 2_000;
            while (System.currentTimeMillis() < hasta) {
                leer(consumidor, ordenId, mensajes);
            }
        }
        assertThat(mensajes).as("el mensaje de la orden sale una sola vez a %s", topico).hasSize(1);
        return mensajes.getFirst();
    }

    /** Los mensajes de esa orden que hay en el tópico después de 2 segundos (20 rondas del outbox). */
    private static List<ConsumerRecord<String, String>> publicados(String topico, String ordenId) {
        List<ConsumerRecord<String, String>> mensajes = new CopyOnWriteArrayList<>();
        try (var consumidor = consumidor(topico)) {
            long hasta = System.currentTimeMillis() + 2_000;
            while (System.currentTimeMillis() < hasta) {
                leer(consumidor, ordenId, mensajes);
            }
        }
        return mensajes;
    }

    private static KafkaConsumer<String, String> consumidor(String topico) {
        Map<String, Object> configuracion = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "prueba-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        var consumidor = new KafkaConsumer<>(configuracion, new StringDeserializer(), new StringDeserializer());
        consumidor.subscribe(List.of(topico));
        return consumidor;
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
