package co.bolsasim.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.EstadoOrden;
import co.bolsasim.eventos.EventoOrden;
import co.bolsasim.eventos.Lado;
import co.bolsasim.eventos.OperacionEjecutada;
import co.bolsasim.eventos.TipoOrden;
import co.bolsasim.eventos.Topicos;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Prueba de integración del motor contra Kafka real en un contenedor (RNF-36): envía comandos a
 * orders.commands como lo haría order-service y lee orders.events y trades.executed con read_committed, como
 * los consumidores reales. Si las transacciones no funcionaran, el motor no podría publicar nada: su
 * KafkaTemplate es transaccional y rechaza envíos fuera de una transacción.
 *
 * <p>Necesita Docker. Si Docker está cerrado, la prueba se salta en vez de fallar.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MotorKafkaIntegracionTest {

    // La misma imagen de docker-compose.yml. Aquí Kafka sí crea los tópicos automáticamente.
    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.2.1");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void unaVentaYUnaCompraQueSeCruzanPublicanLaOperacionYElEstadoDeLasDosOrdenes() {
        String simbolo = "TXA";
        ComandoOrden venta = ComandoOrden.nueva("beto", simbolo, Lado.VENTA, TipoOrden.LIMITE, 5_000L, 100);
        ComandoOrden compra = ComandoOrden.nueva("ana", simbolo, Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100);

        enviar(venta, compra);

        List<OperacionEjecutada> operaciones = leer(Topicos.TRADES_EXECUTED, OperacionEjecutada.class, simbolo, 1);
        assertThat(operaciones).singleElement().satisfies(operacion -> {
            assertThat(operacion.precio()).isEqualTo(5_000L);
            assertThat(operacion.cantidad()).isEqualTo(100);
            assertThat(operacion.ordenCompraId()).isEqualTo(compra.ordenId());
            assertThat(operacion.ordenVentaId()).isEqualTo(venta.ordenId());
        });

        List<EventoOrden> eventos = leer(Topicos.ORDERS_EVENTS, EventoOrden.class, simbolo, 3);
        assertThat(eventos).extracting(EventoOrden::ordenId, EventoOrden::estado).containsExactly(
                tuple(venta.ordenId(), EstadoOrden.ABIERTA),
                tuple(venta.ordenId(), EstadoOrden.EJECUTADA),
                tuple(compra.ordenId(), EstadoOrden.EJECUTADA));
    }

    @Test
    void unComandoRepetidoNoEjecutaDosVecesYReenviaLosMismosEventos() {
        String simbolo = "TXB";
        ComandoOrden venta = ComandoOrden.nueva("beto", simbolo, Lado.VENTA, TipoOrden.LIMITE, 5_000L, 100);
        ComandoOrden compra40 = ComandoOrden.nueva("ana", simbolo, Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 40);
        ComandoOrden compra60 = ComandoOrden.nueva("cris", simbolo, Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 60);

        // La compra de 40 llega dos veces, como si el outbox la hubiera reenviado. Si el motor la ejecutara otra
        // vez, a la venta le quedarían 20 y la compra de 60 no se llenaría completa
        enviar(venta, compra40, compra40, compra60);

        // Venta ABIERTA, 2 de la compra de 40, los mismos 2 reenviados y 2 de la compra de 60
        List<EventoOrden> eventos = leer(Topicos.ORDERS_EVENTS, EventoOrden.class, simbolo, 7);
        assertThat(eventos).hasSize(7);
        assertThat(eventos.stream().map(EventoOrden::eventoId).distinct()).hasSize(5);
        assertThat(eventos.get(3)).isEqualTo(eventos.get(1));
        assertThat(eventos.get(4)).isEqualTo(eventos.get(2));
        assertThat(eventos.get(5)).satisfies(deLaVenta -> {
            assertThat(deLaVenta.ordenId()).isEqualTo(venta.ordenId());
            assertThat(deLaVenta.estado()).isEqualTo(EstadoOrden.EJECUTADA);
            assertThat(deLaVenta.cantidadEjecutada()).isEqualTo(100);
        });
        assertThat(eventos.get(6)).satisfies(deLaCompra -> {
            assertThat(deLaCompra.ordenId()).isEqualTo(compra60.ordenId());
            assertThat(deLaCompra.estado()).isEqualTo(EstadoOrden.EJECUTADA);
        });

        // La operación de la compra de 40 también se reenvía con el mismo id: dos operaciones distintas, no tres
        List<OperacionEjecutada> operaciones = leer(Topicos.TRADES_EXECUTED, OperacionEjecutada.class, simbolo, 3);
        assertThat(operaciones.stream().map(OperacionEjecutada::operacionId).distinct()).hasSize(2);
    }

    @Test
    void unaCompraAMercadoSeDetieneEnElBordeDeLaBandaYCancelaElResto() {
        String simbolo = "TXC";
        ComandoOrden dentro = ComandoOrden.nueva("beto", simbolo, Lado.VENTA, TipoOrden.LIMITE, 5_500L, 30);
        ComandoOrden fuera = ComandoOrden.nueva("cara", simbolo, Lado.VENTA, TipoOrden.LIMITE, 5_510L, 30);
        ComandoOrden compra = ComandoOrden.nueva("ana", simbolo, Lado.COMPRA, TipoOrden.MERCADO, null, 5_000L, 100);

        enviar(dentro, fuera, compra);

        // Dos ABIERTA, la ejecución a 5.500 (un evento por orden) y la CANCELADA de la compra con lo que ejecutó
        List<EventoOrden> eventos = leer(Topicos.ORDERS_EVENTS, EventoOrden.class, simbolo, 5);
        assertThat(eventos.getLast()).satisfies(deLaCompra -> {
            assertThat(deLaCompra.ordenId()).isEqualTo(compra.ordenId());
            assertThat(deLaCompra.estado()).isEqualTo(EstadoOrden.CANCELADA);
            assertThat(deLaCompra.cantidadEjecutada()).isEqualTo(30);
        });
        assertThat(leer(Topicos.TRADES_EXECUTED, OperacionEjecutada.class, simbolo, 1))
                .singleElement().extracting(OperacionEjecutada::precio).isEqualTo(5_500L);
    }

    @Test
    void unComandoAMercadoConElFormatoDeAntesSinReferenciaSeCancelaSinEjecutarse() {
        String simbolo = "TXD";
        enviar(ComandoOrden.nueva("beto", simbolo, Lado.VENTA, TipoOrden.LIMITE, 5_000L, 100));
        // El JSON que publicaba order-service antes de HU-07: sin el campo precioReferencia
        UUID ordenId = UUID.randomUUID();
        String antiguo = """
                {"comandoId":"%s","accion":"NUEVA","ordenId":"%s","usuarioId":"ana","simbolo":"%s","lado":"COMPRA",\
                "tipo":"MERCADO","precioLimite":null,"cantidad":10,"enviadoEn":"2026-10-20T15:00:00Z"}"""
                .formatted(UUID.randomUUID(), ordenId, simbolo);
        enviarJson(simbolo, antiguo);

        List<EventoOrden> eventos = leer(Topicos.ORDERS_EVENTS, EventoOrden.class, simbolo, 2);
        assertThat(eventos.getLast()).satisfies(deLaCompra -> {
            assertThat(deLaCompra.ordenId()).isEqualTo(ordenId);
            assertThat(deLaCompra.estado()).isEqualTo(EstadoOrden.CANCELADA);
            assertThat(deLaCompra.cantidadEjecutada()).isZero();
        });
    }

    private static void enviarJson(String clave, String json) {
        Map<String, Object> configuracion = Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (var productor = new KafkaProducer<>(configuracion, new StringSerializer(), new StringSerializer())) {
            var registro = new ProducerRecord<>(Topicos.ORDERS_COMMANDS, clave, json);
            // El header con el que el deserializador sabe la clase, como lo pone JacksonJsonSerializer
            registro.headers().add("__TypeId__", ComandoOrden.class.getName().getBytes(StandardCharsets.UTF_8));
            productor.send(registro);
        }
    }

    private static void enviar(ComandoOrden... comandos) {
        Map<String, Object> configuracion = Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (var productor = new KafkaProducer<>(configuracion, new StringSerializer(), new JacksonJsonSerializer<ComandoOrden>())) {
            for (ComandoOrden comando : comandos) {
                productor.send(new ProducerRecord<>(Topicos.ORDERS_COMMANDS, comando.simbolo(), comando));
            }
        }
    }

    /** Lee el tópico desde el principio con read_committed hasta tener esa cantidad de mensajes del símbolo. */
    private static <T> List<T> leer(String topico, Class<T> tipo, String simbolo, int cantidad) {
        List<T> leidos = new CopyOnWriteArrayList<>();
        Map<String, Object> configuracion = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "prueba-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        try (var consumidor = new KafkaConsumer<>(configuracion, new StringDeserializer(), new StringDeserializer())) {
            consumidor.subscribe(List.of(topico));
            await().atMost(Duration.ofSeconds(60)).until(() -> {
                consumidor.poll(Duration.ofMillis(500)).forEach(mensaje -> {
                    if (simbolo.equals(mensaje.key())) {
                        leidos.add(JSON.readValue(mensaje.value(), tipo));
                    }
                });
                return leidos.size() >= cantidad;
            });
        }
        return leidos;
    }
}
