package co.bolsasim.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.bolsasim.eventos.OperacionEjecutada;
import co.bolsasim.eventos.Topicos;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Prueba de integración del último precio y la variación del día (HU-14, RF-11, RF-40): publica trades.executed
 * como el motor y comprueba qué guarda market-data-service en OPERACION y qué responde GET /api/instrumentos.
 * Cada prueba usa un instrumento distinto de los 10 iniciales, así no dependen del orden.
 *
 * <p>Necesita Docker. Si Docker está cerrado, la prueba se salta en vez de fallar.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class CotizacionesIntegracionTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

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
    private JdbcClient jdbc;

    @Test
    void sinOperacionesElUltimoPrecioEsElInicialYLaVariacionEsCero() throws Exception {
        consultar("PACF")
                .andExpect(jsonPath("$.precioInicial").value(40_000))
                .andExpect(jsonPath("$.ultimoPrecio").value(40_000))
                .andExpect(jsonPath("$.precioApertura").value(40_000))
                .andExpect(jsonPath("$.variacion").value(0))
                .andExpect(jsonPath("$.variacionPorcentaje").value(0.0));
    }

    @Test
    void unaOperacionDeHoyCambiaElUltimoPrecioYLaVariacionSeMideContraElPrecioInicial() throws Exception {
        publicar(operacion("ANDX", 5_100, ahora().minusSeconds(2)));
        publicar(operacion("ANDX", 5_250, ahora()));
        esperarOperaciones("ANDX", 2);

        consultar("ANDX")
                .andExpect(jsonPath("$.ultimoPrecio").value(5_250))
                .andExpect(jsonPath("$.precioApertura").value(5_000))
                .andExpect(jsonPath("$.variacion").value(250))
                .andExpect(jsonPath("$.variacionPorcentaje").value(5.0))
                // Los campos de siempre no cambian
                .andExpect(jsonPath("$.precioInicial").value(5_000))
                .andExpect(jsonPath("$.tick").value(10));
        assertThat(secuencias("ANDX")).containsExactly(1L, 2L);
    }

    @Test
    void laMismaOperacionDosVecesSeGuardaUnaSolaVez() throws Exception {
        OperacionEjecutada repetida = operacion("CAFE", 12_600, ahora().minusSeconds(1));
        publicar(repetida);
        publicar(repetida);
        // La tercera llega después de las dos copias: cuando ya está guardada, las dos anteriores ya se procesaron
        publicar(operacion("CAFE", 12_650, ahora()));
        esperarOperaciones("CAFE", 2);

        assertThat(secuencias("CAFE")).containsExactly(1L, 2L);
        assertThat(jdbc.sql("SELECT count(*) FROM operacion WHERE id = :id").param("id", repetida.operacionId())
                .query(Integer.class).single()).isEqualTo(1);
        consultar("CAFE").andExpect(jsonPath("$.ultimoPrecio").value(12_650));
    }

    @Test
    void laUltimaOperacionDeAyerFijaLaAperturaDeHoy() throws Exception {
        Instant inicioDeHoy = LocalDate.now(BOGOTA).atStartOfDay(BOGOTA).toInstant();
        publicar(operacion("BGUA", 25_500, inicioDeHoy.minus(Duration.ofHours(5))));
        publicar(operacion("BGUA", 26_000, inicioDeHoy.minus(Duration.ofMinutes(1))));
        esperarOperaciones("BGUA", 2);

        // Hoy no ha operado: el último precio es el cierre de ayer y la variación es 0, no la de ayer
        consultar("BGUA")
                .andExpect(jsonPath("$.ultimoPrecio").value(26_000))
                .andExpect(jsonPath("$.precioApertura").value(26_000))
                .andExpect(jsonPath("$.variacion").value(0))
                .andExpect(jsonPath("$.variacionPorcentaje").value(0.0));

        publicar(operacion("BGUA", 26_650, ahora()));
        esperarOperaciones("BGUA", 3);

        // 650 / 26.000 = 2,5 %: contra el cierre de ayer, no contra el precio inicial (25.000)
        consultar("BGUA")
                .andExpect(jsonPath("$.ultimoPrecio").value(26_650))
                .andExpect(jsonPath("$.precioApertura").value(26_000))
                .andExpect(jsonPath("$.variacion").value(650))
                .andExpect(jsonPath("$.variacionPorcentaje").value(2.5));
    }

    @Test
    void siElPrecioBajaLaVariacionEsNegativa() throws Exception {
        publicar(operacion("TAYR", 3_150, ahora()));
        esperarOperaciones("TAYR", 1);

        consultar("TAYR")
                .andExpect(jsonPath("$.ultimoPrecio").value(3_150))
                .andExpect(jsonPath("$.variacion").value(-350))
                .andExpect(jsonPath("$.variacionPorcentaje").value(-10.0));
    }

    @Test
    void laListaTraeLosPreciosDeTodosEnOrdenYUnaOperacionDeUnSimboloDesconocidoNoSeGuarda() throws Exception {
        publicar(operacion("NOHAY", 1_000, ahora().minusSeconds(1)));
        publicar(operacion("QUIM", 8_400, ahora()));
        esperarOperaciones("QUIM", 1);

        assertThat(secuencias("NOHAY")).isEmpty();
        mvc.perform(get("/api/instrumentos").header(HttpHeaders.AUTHORIZATION, token("INVERSIONISTA")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].simbolo").value("ANDX"))
                .andExpect(jsonPath("$[?(@.simbolo == 'QUIM')].ultimoPrecio").value(8_400))
                .andExpect(jsonPath("$[?(@.simbolo == 'QUIM')].variacion").value(400))
                .andExpect(jsonPath("$[?(@.simbolo == 'QUIM')].variacionPorcentaje").value(5.0))
                // SALV no opera en ninguna prueba
                .andExpect(jsonPath("$[?(@.simbolo == 'SALV')].ultimoPrecio").value(9_500))
                .andExpect(jsonPath("$[?(@.simbolo == 'SALV')].variacion").value(0));
    }

    @Test
    void suspenderUnInstrumentoRespondeConSusPreciosDelDia() throws Exception {
        publicar(operacion("FLOR", 1_560, ahora()));
        esperarOperaciones("FLOR", 1);

        mvc.perform(post("/api/instrumentos/FLOR/suspender").header(HttpHeaders.AUTHORIZATION, token("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("SUSPENDIDO"))
                .andExpect(jsonPath("$.ultimoPrecio").value(1_560))
                .andExpect(jsonPath("$.variacionPorcentaje").value(4.0));
    }

    private ResultActions consultar(String simbolo) throws Exception {
        return mvc.perform(get("/api/instrumentos/" + simbolo).header(HttpHeaders.AUTHORIZATION, token("INVERSIONISTA")))
                .andExpect(status().isOk());
    }

    private void publicar(OperacionEjecutada operacion) {
        kafkaTemplate.send(Topicos.TRADES_EXECUTED, operacion.simbolo(), operacion);
    }

    private void esperarOperaciones(String simbolo, int cantidad) {
        await().atMost(Duration.ofSeconds(30)).until(() -> secuencias(simbolo).size() == cantidad);
    }

    private List<Long> secuencias(String simbolo) {
        return jdbc.sql("SELECT secuencia FROM operacion WHERE simbolo = :simbolo ORDER BY secuencia")
                .param("simbolo", simbolo).query(Long.class).list();
    }

    private static OperacionEjecutada operacion(String simbolo, long precio, Instant ejecutadaEn) {
        return new OperacionEjecutada(UUID.randomUUID(), simbolo, precio, 10, UUID.randomUUID(),
                UUID.randomUUID().toString(), UUID.randomUUID(), UUID.randomUUID().toString(), ejecutadaEn);
    }

    private static Instant ahora() {
        return Instant.now();
    }

    private static String token(String rol) {
        return "Bearer " + TokensDePrueba.valido(rol);
    }
}
