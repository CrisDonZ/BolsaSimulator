package co.bolsasim.order.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.Lado;
import co.bolsasim.eventos.SolicitudReserva;
import co.bolsasim.eventos.TipoOrden;
import co.bolsasim.eventos.Topicos;
import co.bolsasim.order.orden.Orden;
import co.bolsasim.order.orden.RepositorioOrdenes;
import co.bolsasim.order.outbox.Outbox;
import co.bolsasim.order.web.CrearOrdenRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Prueba de integración de HU-10 contra PostgreSQL real (RNF-36): solicitudes repetidas y simultáneas con la
 * misma Idempotency-Key. En cada caso cuenta cuántas órdenes y cuántas filas del outbox quedan, porque cada
 * fila del outbox es una solicitud de reserva: una orden que llegaría al motor. Kafka no hace falta: los
 * listeners y el publicador del outbox están apagados.
 *
 * <p>Necesita Docker. Si Docker está cerrado, la prueba se salta en vez de fallar.
 */
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "bolsasim.outbox.programado=false"})
@Testcontainers(disabledWithoutDocker = true)
class IdempotenciaIntegracionTest {

    private static final UUID ANA = UUID.randomUUID();
    private static final UUID BETO = UUID.randomUUID();
    private static final CrearOrdenRequest COMPRA = new CrearOrdenRequest("ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100);

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private ServicioOrdenes servicio;

    @Autowired
    private RepositorioOrdenes ordenes;

    @Autowired
    private Outbox outbox;

    @Autowired
    private TransactionTemplate transaccion;

    @Autowired
    private JdbcClient jdbc;

    private final ExecutorService hilos = Executors.newFixedThreadPool(8);

    @BeforeEach
    void anaYBetoRegistradosYAndxActivo() {
        jdbc.sql("DELETE FROM outbox").update();
        jdbc.sql("DELETE FROM orden").update();
        jdbc.sql("DELETE FROM usuario_registrado").update();
        jdbc.sql("DELETE FROM instrumento").update();
        for (UUID usuario : List.of(ANA, BETO)) {
            jdbc.sql("INSERT INTO usuario_registrado (usuario_id, registrado_en) VALUES (:id, now())")
                    .param("id", usuario).update();
        }
        jdbc.sql("""
                        INSERT INTO instrumento (simbolo, estado, tick, precio_inicial, actualizado_en)
                        VALUES ('ANDX', 'ACTIVO', 10, 5000, now())""")
                .update();
    }

    @AfterEach
    void cerrarHilos() {
        hilos.shutdownNow();
    }

    @Test
    void laMismaSolicitudDosVecesSeguidasDejaUnaOrdenYUnaSolicitud() {
        String clave = nuevaClave();

        OrdenCreada primera = servicio.crear(ANA.toString(), clave, COMPRA);
        OrdenCreada segunda = servicio.crear(ANA.toString(), clave, COMPRA);

        assertThat(primera.repetida()).isFalse();
        assertThat(segunda.repetida()).isTrue();
        assertThat(segunda.orden().getId()).isEqualTo(primera.orden().getId());
        assertThat(ordenes()).isEqualTo(1);
        assertThat(solicitudes()).isEqualTo(1);
    }

    @Test
    void ochoSolicitudesALaVezConLaMismaClaveDejanUnaOrdenYUnaSolicitud() throws Exception {
        String clave = nuevaClave();
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<OrdenCreada>> resultados = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            resultados.add(hilos.submit(() -> {
                largada.await();
                return servicio.crear(ANA.toString(), clave, COMPRA);
            }));
        }

        largada.countDown();

        List<OrdenCreada> creadas = new ArrayList<>();
        for (Future<OrdenCreada> resultado : resultados) {
            creadas.add(resultado.get(30, TimeUnit.SECONDS));
        }
        // Todas reciben 202 con la misma orden; solo una la creó
        assertThat(creadas).extracting(creada -> creada.orden().getId()).containsOnly(creadas.getFirst().orden().getId());
        assertThat(creadas).filteredOn(creada -> !creada.repetida()).hasSize(1);
        assertThat(ordenes()).isEqualTo(1);
        assertThat(solicitudes()).isEqualTo(1);
    }

    /**
     * El doble clic exacto: la primera solicitud ya insertó la orden pero todavía no confirma. La segunda no la
     * ve al buscar, intenta insertar y PostgreSQL la hace esperar en el índice único. Cuando la primera
     * confirma, la segunda recibe la orden de la primera.
     */
    @Test
    void laSegundaEsperaALaPrimeraYRecibeSuOrden() throws Exception {
        String clave = nuevaClave();
        CountDownLatch insertada = new CountDownLatch(1);
        CountDownLatch confirmar = new CountDownLatch(1);
        Future<Orden> primera = hilos.submit(() -> primeraSinConfirmar(clave, insertada, confirmar, false));
        assertThat(insertada.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<OrdenCreada> segunda = CompletableFuture.supplyAsync(
                () -> servicio.crear(ANA.toString(), clave, COMPRA), hilos);

        // Mientras la primera no confirme, la segunda espera en el índice único
        Thread.sleep(1_000);
        assertThat(segunda).isNotDone();

        confirmar.countDown();
        OrdenCreada resultado = segunda.get(10, TimeUnit.SECONDS);

        assertThat(resultado.repetida()).isTrue();
        assertThat(resultado.orden().getId()).isEqualTo(primera.get().getId());
        assertThat(ordenes()).isEqualTo(1);
        assertThat(solicitudes()).isEqualTo(1);
    }

    @Test
    void siLaPrimeraFallaLaSegundaCreaLaOrden() throws Exception {
        String clave = nuevaClave();
        CountDownLatch insertada = new CountDownLatch(1);
        CountDownLatch terminar = new CountDownLatch(1);
        Future<Orden> primera = hilos.submit(() -> primeraSinConfirmar(clave, insertada, terminar, true));
        assertThat(insertada.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<OrdenCreada> segunda = CompletableFuture.supplyAsync(
                () -> servicio.crear(ANA.toString(), clave, COMPRA), hilos);
        Thread.sleep(500);
        terminar.countDown();

        OrdenCreada resultado = segunda.get(10, TimeUnit.SECONDS);
        assertThat(resultado.repetida()).isFalse();
        assertThatThrownBy(primera::get).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(ordenes()).isEqualTo(1);
        assertThat(solicitudes()).isEqualTo(1);
    }

    @Test
    void laMismaClaveDeDosUsuariosSonDosOrdenes() {
        String clave = nuevaClave();

        OrdenCreada deAna = servicio.crear(ANA.toString(), clave, COMPRA);
        OrdenCreada deBeto = servicio.crear(BETO.toString(), clave, COMPRA);

        assertThat(deBeto.repetida()).isFalse();
        assertThat(deBeto.orden().getId()).isNotEqualTo(deAna.orden().getId());
        assertThat(ordenes()).isEqualTo(2);
        assertThat(solicitudes()).isEqualTo(2);
    }

    @Test
    void laMismaClaveConOtroCuerpoSeRechazaYNoCreaNada() {
        String clave = nuevaClave();
        servicio.crear(ANA.toString(), clave, COMPRA);

        assertThatThrownBy(() -> servicio.crear(ANA.toString(), clave,
                new CrearOrdenRequest("ANDX", Lado.VENTA, TipoOrden.LIMITE, 5_000L, 100)))
                .isInstanceOf(ClaveIdempotenciaReusadaException.class);
        assertThat(ordenes()).isEqualTo(1);
        assertThat(solicitudes()).isEqualTo(1);
    }

    @Test
    void unaSolicitudRechazadaNoGastaLaClave() {
        String clave = nuevaClave();

        assertThatThrownBy(() -> servicio.crear(ANA.toString(), clave,
                new CrearOrdenRequest("NADA", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100)))
                .isInstanceOf(InstrumentoNoDisponibleException.class);
        assertThat(ordenes()).isZero();

        // Corregida, se reenvía con la misma clave y se crea
        OrdenCreada corregida = servicio.crear(ANA.toString(), clave, COMPRA);
        assertThat(corregida.repetida()).isFalse();
        assertThat(ordenes()).isEqualTo(1);
        assertThat(solicitudes()).isEqualTo(1);
    }

    /**
     * Hace lo mismo que ServicioOrdenes al crear la orden, pero se detiene antes de confirmar hasta que le
     * avisen; luego confirma, o falla si se le pide.
     */
    private Orden primeraSinConfirmar(String clave, CountDownLatch insertada, CountDownLatch seguir, boolean fallar) {
        return transaccion.execute(estado -> {
            ComandoOrden comando = ComandoOrden.nueva(ANA.toString(), "ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100);
            Orden orden = ordenes.saveAndFlush(Orden.nueva(comando, ANA, clave, Instant.now().truncatedTo(ChronoUnit.MICROS)));
            outbox.guardar(Topicos.ORDERS_REQUESTED, ANA.toString(), SolicitudReserva.de(comando, 5_000L));
            insertada.countDown();
            try {
                seguir.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (fallar) {
                throw new IllegalStateException("la primera solicitud falla antes de confirmar");
            }
            return orden;
        });
    }

    private static String nuevaClave() {
        return UUID.randomUUID().toString();
    }

    private int ordenes() {
        return jdbc.sql("SELECT count(*) FROM orden").query(Integer.class).single();
    }

    private int solicitudes() {
        return jdbc.sql("SELECT count(*) FROM outbox").query(Integer.class).single();
    }
}
