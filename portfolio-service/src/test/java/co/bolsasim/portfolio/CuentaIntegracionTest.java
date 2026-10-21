package co.bolsasim.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.bolsasim.eventos.EjecucionOrden;
import co.bolsasim.eventos.EstadoOrden;
import co.bolsasim.eventos.EventoOrden;
import co.bolsasim.eventos.Lado;
import co.bolsasim.eventos.SolicitudReserva;
import co.bolsasim.eventos.TipoOrden;
import co.bolsasim.eventos.Topicos;
import co.bolsasim.eventos.UsuarioRegistrado;
import co.bolsasim.portfolio.servicio.ServicioCuentas;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Prueba de integración: portfolio-service completo contra PostgreSQL y Kafka reales en contenedores (RNF-36).
 * Publica users.registered como auth-service, orders.requested como order-service y orders.events como el motor,
 * lee las respuestas en reservations.events y consulta la cuenta con tokens firmados con la clave de
 * {@link TokensDePrueba}.
 *
 * <p>Necesita Docker. Si Docker está cerrado, la prueba se salta en vez de fallar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
// Cierra el contexto al terminar: si no, el consumidor de Kafka y el publicador del outbox siguen buscando un
// Kafka y un PostgreSQL que ya se apagaron
@DirtiesContext
class CuentaIntegracionTest {

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
    private ServicioCuentas servicio;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void cuandoLlegaUsersRegisteredSeAbreLaCuentaConElSaldoInicialYUnEventoRepetidoNoLaDuplica() throws Exception {
        UUID ana = UUID.randomUUID();
        UsuarioRegistrado evento = UsuarioRegistrado.de(ana, "ana_inv", "INVERSIONISTA", Instant.now());

        kafkaTemplate.send(Topicos.USERS_REGISTERED, ana.toString(), evento);
        kafkaTemplate.send(Topicos.USERS_REGISTERED, ana.toString(), evento);
        await().atMost(Duration.ofSeconds(30)).until(() -> cuentasDe(ana) == 1);
        // Espera a que el repetido también se procese
        await().atMost(Duration.ofSeconds(30)).until(() -> procesado(evento.eventoId()));

        assertThat(cuentasDe(ana)).isEqualTo(1);
        assertThat(movimientosDe(ana)).isEqualTo(1);
        mvc.perform(get("/api/cuenta").header(HttpHeaders.AUTHORIZATION, "Bearer " + TokensDePrueba.de(ana, "INVERSIONISTA")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saldoDisponible").value(10_000_000))
                .andExpect(jsonPath("$.saldoReservado").value(0))
                .andExpect(jsonPath("$.saldoTotal").value(10_000_000));
        mvc.perform(get("/api/cuenta/movimientos").header(HttpHeaders.AUTHORIZATION, "Bearer " + TokensDePrueba.de(ana, "INVERSIONISTA")))
                .andExpect(jsonPath("$.movimientos.length()").value(1))
                .andExpect(jsonPath("$.movimientos[0].tipo").value("SALDO_INICIAL"))
                .andExpect(jsonPath("$.movimientos[0].monto").value(10_000_000));
    }

    @Test
    void laCompraDeHu05ReservaPorKafkaYAlEjecutarseA4900SePaganYSeLiberanLos10000() throws Exception {
        UUID ana = UUID.randomUUID();
        UUID ordenId = UUID.randomUUID();
        String token = "Bearer " + TokensDePrueba.de(ana, "INVERSIONISTA");

        // order-service pide la reserva de una compra límite de 100 a 5.000; la solicitud llega dos veces
        SolicitudReserva solicitud = new SolicitudReserva(UUID.randomUUID(), ordenId, ana.toString(), "ANDX",
                Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100, 5_000, Instant.now());
        kafkaTemplate.send(Topicos.ORDERS_REQUESTED, ana.toString(), solicitud);
        kafkaTemplate.send(Topicos.ORDERS_REQUESTED, ana.toString(), solicitud);

        // La respuesta sale una sola vez por reservations.events, con el usuario como clave
        ConsumerRecord<String, String> respuesta = respuestaPublicada(ordenId);
        assertThat(respuesta.key()).isEqualTo(ana.toString());
        assertThat(respuesta.value()).contains("\"resultado\":\"ACEPTADA\"", "\"montoReservado\":500000");
        mvc.perform(get("/api/cuenta").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.saldoDisponible").value(9_500_000))
                .andExpect(jsonPath("$.saldoReservado").value(500_000))
                .andExpect(jsonPath("$.saldoTotal").value(10_000_000));

        // El motor la cruza con una venta de 100 a 4.900 (el evento llega repetido)
        EventoOrden ejecutada = new EventoOrden(UUID.randomUUID(), ordenId, ana.toString(), "ANDX",
                EstadoOrden.EJECUTADA, 100, 100, new EjecucionOrden(UUID.randomUUID(), 4_900, 100), null, Instant.now());
        kafkaTemplate.send(Topicos.ORDERS_EVENTS, "ANDX", ejecutada);
        kafkaTemplate.send(Topicos.ORDERS_EVENTS, "ANDX", ejecutada);
        await().atMost(Duration.ofSeconds(30)).until(() -> procesado(ejecutada.eventoId()));

        mvc.perform(get("/api/cuenta").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.saldoDisponible").value(9_510_000))
                .andExpect(jsonPath("$.saldoReservado").value(0))
                .andExpect(jsonPath("$.saldoTotal").value(9_510_000));
        mvc.perform(get("/api/cuenta/movimientos").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.movimientos.length()").value(4))
                .andExpect(jsonPath("$.movimientos[?(@.tipo == 'RESERVA')].monto").value(-500_000))
                .andExpect(jsonPath("$.movimientos[?(@.tipo == 'COMPRA')].monto").value(-490_000))
                .andExpect(jsonPath("$.movimientos[?(@.tipo == 'COMPRA')].cantidad").value(100))
                .andExpect(jsonPath("$.movimientos[?(@.tipo == 'LIBERACION')].monto").value(10_000));
    }

    @Test
    void sinSaldoLaReservaSeRechazaPorKafkaConElMotivo() {
        UUID beto = UUID.randomUUID();
        UUID ordenId = UUID.randomUUID();

        kafkaTemplate.send(Topicos.ORDERS_REQUESTED, beto.toString(), new SolicitudReserva(UUID.randomUUID(), ordenId,
                beto.toString(), "ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 10_000, 5_000, Instant.now()));

        assertThat(respuestaPublicada(ordenId).value()).contains("\"resultado\":\"RECHAZADA\"",
                "Saldo insuficiente: tienes 10.000.000 COP disponibles y la orden necesita 50.000.000 COP.");
    }

    @Test
    void unUsuarioSinEventoRecibeSuCuentaAlConsultarlaYSuEventoTardioNoLaDuplica() throws Exception {
        // Se registró antes de que existiera portfolio-service: su users.registered ya no está en Kafka
        UUID beto = UUID.randomUUID();

        mvc.perform(get("/api/cuenta").header(HttpHeaders.AUTHORIZATION, "Bearer " + TokensDePrueba.de(beto, "BOT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saldoDisponible").value(10_000_000));

        UsuarioRegistrado tardio = UsuarioRegistrado.de(beto, "beto_bot", "BOT", Instant.now());
        kafkaTemplate.send(Topicos.USERS_REGISTERED, beto.toString(), tardio);
        await().atMost(Duration.ofSeconds(30)).until(() -> procesado(tardio.eventoId()));

        assertThat(cuentasDe(beto)).isEqualTo(1);
        assertThat(movimientosDe(beto)).isEqualTo(1);
    }

    @Test
    void cadaUsuarioVeSoloSuCuenta() throws Exception {
        UUID ana = UUID.randomUUID();
        UUID beto = UUID.randomUUID();
        servicio.abrir(ana);
        jdbc.sql("UPDATE cuenta SET saldo_disponible = 123 WHERE usuario_id = :id").param("id", ana).update();

        // Las rutas no llevan id: con el token de Beto se ve la cuenta de Beto, nunca la de Ana
        mvc.perform(get("/api/cuenta").header(HttpHeaders.AUTHORIZATION, "Bearer " + TokensDePrueba.de(beto, "INVERSIONISTA")))
                .andExpect(jsonPath("$.saldoDisponible").value(10_000_000));
        mvc.perform(get("/api/cuenta").header(HttpHeaders.AUTHORIZATION, "Bearer " + TokensDePrueba.de(ana, "INVERSIONISTA")))
                .andExpect(jsonPath("$.saldoDisponible").value(123));
    }

    @Test
    void unEventoDeUnAdminNoAbreCuentaYSuConsultaResponde404() throws Exception {
        UUID admin = UUID.randomUUID();
        UsuarioRegistrado evento = UsuarioRegistrado.de(admin, "admin", "ADMIN", Instant.now());

        kafkaTemplate.send(Topicos.USERS_REGISTERED, admin.toString(), evento);
        await().atMost(Duration.ofSeconds(30)).until(() -> procesado(evento.eventoId()));

        assertThat(cuentasDe(admin)).isZero();
        mvc.perform(get("/api/cuenta").header(HttpHeaders.AUTHORIZATION, "Bearer " + TokensDePrueba.de(admin, "ADMIN")))
                .andExpect(status().isNotFound());
        assertThat(cuentasDe(admin)).isZero();
    }

    @Test
    void ochoAperturasALaVezDelMismoUsuarioDejanUnaCuentaYUnSaldoInicial() throws Exception {
        UUID cris = UUID.randomUUID();
        ExecutorService hilos = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch largada = new CountDownLatch(1);
            List<Future<?>> resultados = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                resultados.add(hilos.submit(() -> {
                    largada.await();
                    return servicio.abrir(cris);
                }));
            }
            largada.countDown();
            for (Future<?> resultado : resultados) {
                resultado.get(30, TimeUnit.SECONDS);
            }
        } finally {
            hilos.shutdownNow();
        }

        assertThat(cuentasDe(cris)).isEqualTo(1);
        assertThat(movimientosDe(cris)).isEqualTo(1);
    }

    @Test
    void laBaseRechazaUnSaldoNegativo() {
        UUID dani = UUID.randomUUID();
        servicio.abrir(dani);

        assertThatThrownBy(() -> jdbc.sql("UPDATE cuenta SET saldo_disponible = saldo_disponible - 10000001 "
                + "WHERE usuario_id = :id").param("id", dani).update())
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE cuenta SET saldo_reservado = -1 WHERE usuario_id = :id")
                .param("id", dani).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private int cuentasDe(UUID usuario) {
        return jdbc.sql("SELECT count(*) FROM cuenta WHERE usuario_id = :id").param("id", usuario)
                .query(Integer.class).single();
    }

    private int movimientosDe(UUID usuario) {
        return jdbc.sql("""
                        SELECT count(*) FROM movimiento m JOIN cuenta c ON c.id = m.cuenta_id
                        WHERE c.usuario_id = :id""")
                .param("id", usuario).query(Integer.class).single();
    }

    /**
     * Lee reservations.events desde el principio hasta que llega la respuesta de esa orden, y 2 segundos más (20
     * rondas del outbox): una respuesta repetida llegaría en ese tiempo.
     */
    private static ConsumerRecord<String, String> respuestaPublicada(UUID ordenId) {
        List<ConsumerRecord<String, String>> respuestas = new CopyOnWriteArrayList<>();
        Map<String, Object> configuracion = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "prueba-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumidor = new KafkaConsumer<>(configuracion, new StringDeserializer(), new StringDeserializer())) {
            consumidor.subscribe(List.of(Topicos.RESERVATIONS_EVENTS));
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                leer(consumidor, ordenId, respuestas);
                return !respuestas.isEmpty();
            });
            long hasta = System.currentTimeMillis() + 2_000;
            while (System.currentTimeMillis() < hasta) {
                leer(consumidor, ordenId, respuestas);
            }
        }
        assertThat(respuestas).as("la respuesta de la reserva sale una sola vez").hasSize(1);
        return respuestas.getFirst();
    }

    private static void leer(KafkaConsumer<String, String> consumidor, UUID ordenId,
                             List<ConsumerRecord<String, String>> respuestas) {
        consumidor.poll(Duration.ofMillis(200)).forEach(mensaje -> {
            if (mensaje.value().contains("\"ordenId\":\"" + ordenId + "\"")) {
                respuestas.add(mensaje);
            }
        });
    }

    private boolean procesado(UUID eventoId) {
        return jdbc.sql("SELECT count(*) FROM evento_procesado WHERE id_evento = :id").param("id", eventoId)
                .query(Integer.class).single() == 1;
    }
}
