package co.bolsasim.portfolio.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.bolsasim.eventos.EjecucionOrden;
import co.bolsasim.eventos.EstadoOrden;
import co.bolsasim.eventos.EventoOrden;
import co.bolsasim.eventos.Lado;
import co.bolsasim.eventos.ResultadoReserva;
import co.bolsasim.eventos.SolicitudReserva;
import co.bolsasim.eventos.TipoOrden;
import co.bolsasim.eventos.Topicos;
import co.bolsasim.portfolio.cuenta.Cuenta;
import co.bolsasim.portfolio.cuenta.RepositorioCuentas;
import co.bolsasim.portfolio.posicion.Posicion;
import co.bolsasim.portfolio.posicion.RepositorioPosiciones;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Prueba de integración de las reservas y la liquidación de las compras contra PostgreSQL real (RNF-36), con los
 * criterios de HU-05: la reserva de una compra límite, su ejecución a un precio mejor con la liberación del
 * sobrante, el rechazo por saldo insuficiente, los eventos repetidos y los imposibles. Los eventos se aplican
 * llamando a los servicios directamente, como lo hacen los listeners; Kafka no hace falta.
 *
 * <p>Necesita Docker. Si Docker está cerrado, la prueba se salta en vez de fallar.
 */
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "bolsasim.outbox.programado=false"})
@Testcontainers(disabledWithoutDocker = true)
class ReservasYLiquidacionIntegracionTest {

    private static final long SALDO_INICIAL = 10_000_000;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private ServicioReservas reservas;

    @Autowired
    private ServicioLiquidacion liquidacion;

    @Autowired
    private ServicioCuentas servicioCuentas;

    @Autowired
    private RepositorioCuentas cuentas;

    @Autowired
    private RepositorioPosiciones posiciones;

    @Autowired
    private JdbcClient jdbc;

    private UUID ana;

    @BeforeEach
    void anaConSuCuentaNueva() {
        ana = UUID.randomUUID();
        servicioCuentas.abrir(ana);
    }

    @Test
    void conUnMillonDisponibleUnaCompraDe100A5000Reserva500000() {
        conDisponible(1_000_000);
        SolicitudReserva compra = compraLimite(5_000, 100);

        assertThat(reservas.procesar(compra)).isTrue();

        Cuenta cuenta = cuenta();
        assertThat(cuenta.saldoDisponible()).isEqualTo(500_000);
        assertThat(cuenta.saldoReservado()).isEqualTo(500_000);
        assertThat(reserva(compra.ordenId())).isEqualTo(new FilaReserva(500_000, 0, 0, "ACTIVA"));
        assertThat(movimientos("RESERVA")).containsExactly(-500_000L);
        Respuesta respuesta = respuestaDe(compra);
        assertThat(respuesta.resultado()).isEqualTo(ResultadoReserva.ACEPTADA.name());
        assertThat(respuesta.monto()).isEqualTo(500_000);
        assertThat(respuesta.clave()).isEqualTo(ana.toString());
    }

    @Test
    void sinSaldoSuficienteLaCompraSeRechazaConElMotivoYNoCambiaNada() {
        conDisponible(1_000_000);
        SolicitudReserva compra = compraLimite(5_000, 10_000);

        assertThat(reservas.procesar(compra)).isTrue();

        Respuesta respuesta = respuestaDe(compra);
        assertThat(respuesta.resultado()).isEqualTo(ResultadoReserva.RECHAZADA.name());
        assertThat(respuesta.motivo())
                .isEqualTo("Saldo insuficiente: tienes 1.000.000 COP disponibles y la orden necesita 50.000.000 COP.");
        assertThat(cuenta().saldoDisponible()).isEqualTo(1_000_000);
        assertThat(cuenta().saldoReservado()).isZero();
        assertThat(reservasDeLaCuenta()).isZero();
        assertThat(movimientos("RESERVA")).isEmpty();
    }

    @Test
    void unaSolicitudRepetidaReservaUnaSolaVez() {
        SolicitudReserva compra = compraLimite(5_000, 100);

        assertThat(reservas.procesar(compra)).isTrue();
        assertThat(reservas.procesar(compra)).isFalse();

        assertThat(cuenta().saldoReservado()).isEqualTo(500_000);
        assertThat(movimientos("RESERVA")).hasSize(1);
        assertThat(respuestasDe(compra)).isEqualTo(1);
    }

    @Test
    void otraSolicitudDeLaMismaOrdenTampocoReservaDosVeces() {
        SolicitudReserva compra = compraLimite(5_000, 100);
        SolicitudReserva otra = new SolicitudReserva(UUID.randomUUID(), compra.ordenId(), compra.usuarioId(), "ANDX",
                Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100, 5_000, Instant.now());

        reservas.procesar(compra);
        assertThat(reservas.procesar(otra)).isFalse();

        assertThat(cuenta().saldoReservado()).isEqualTo(500_000);
        assertThat(respuestasDe(compra)).isEqualTo(1);
    }

    @Test
    void dosComprasALaVezQueJuntasPasanDelDisponibleNoGastanDosVecesElMismoSaldo() throws Exception {
        conDisponible(800_000);
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        List<SolicitudReserva> compras = List.of(compraLimite(5_000, 100), compraLimite(5_000, 100));
        try {
            CountDownLatch largada = new CountDownLatch(1);
            List<Future<?>> resultados = new ArrayList<>();
            for (SolicitudReserva compra : compras) {
                resultados.add(hilos.submit(() -> {
                    largada.await();
                    return reservas.procesar(compra);
                }));
            }
            largada.countDown();
            for (Future<?> resultado : resultados) {
                resultado.get(30, TimeUnit.SECONDS);
            }
        } finally {
            hilos.shutdownNow();
        }

        // Cada una necesita 500.000: una se acepta y la otra se rechaza, aunque lleguen en el mismo instante
        assertThat(compras.stream().map(compra -> respuestaDe(compra).resultado()))
                .containsExactlyInAnyOrder("ACEPTADA", "RECHAZADA");
        assertThat(cuenta().saldoDisponible()).isEqualTo(300_000);
        assertThat(cuenta().saldoReservado()).isEqualTo(500_000);
    }

    @Test
    void unaCompraAMercadoReservaDiezPorCientoMasDelUltimoPrecio() {
        SolicitudReserva compra = new SolicitudReserva(UUID.randomUUID(), UUID.randomUUID(), ana.toString(), "ANDX",
                Lado.COMPRA, TipoOrden.MERCADO, null, 10, 5_000, Instant.now());

        reservas.procesar(compra);

        assertThat(reserva(compra.ordenId())).isEqualTo(new FilaReserva(55_000, 0, 0, "ACTIVA"));
    }

    @Test
    void unaVentaSeAceptaSinReservarNadaHastaHu06() {
        SolicitudReserva venta = new SolicitudReserva(UUID.randomUUID(), UUID.randomUUID(), ana.toString(), "ANDX",
                Lado.VENTA, TipoOrden.LIMITE, 5_000L, 100, 5_000, Instant.now());

        reservas.procesar(venta);

        assertThat(respuestaDe(venta).resultado()).isEqualTo("ACEPTADA");
        assertThat(cuenta().saldoDisponible()).isEqualTo(SALDO_INICIAL);
        assertThat(reservasDeLaCuenta()).isZero();
    }

    @Test
    void siLaCuentaTodaviaNoExisteSeAbreConElSaldoInicialAntesDeReservar() {
        UUID beto = UUID.randomUUID();
        SolicitudReserva compra = new SolicitudReserva(UUID.randomUUID(), UUID.randomUUID(), beto.toString(), "ANDX",
                Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100, 5_000, Instant.now());

        reservas.procesar(compra);

        assertThat(cuentas.buscarPorUsuario(beto).orElseThrow().saldoDisponible()).isEqualTo(SALDO_INICIAL - 500_000);
    }

    @Test
    void conUnaVentaDe100A4900EnElLibroLaCompraSeEjecutaA4900YSeLiberan10000() {
        SolicitudReserva compra = compraLimite(5_000, 100);
        reservas.procesar(compra);

        assertThat(liquidacion.aplicar(evento(compra, EstadoOrden.EJECUTADA, 100, ejecucion(4_900, 100)))).isTrue();

        // Se pagan 490.000 de la reserva y los 10.000 que sobran vuelven al disponible
        Cuenta cuenta = cuenta();
        assertThat(cuenta.saldoReservado()).isZero();
        assertThat(cuenta.saldoDisponible()).isEqualTo(SALDO_INICIAL - 490_000);
        assertThat(reserva(compra.ordenId())).isEqualTo(new FilaReserva(500_000, 490_000, 10_000, "CONSUMIDA"));
        assertThat(movimientos("COMPRA")).containsExactly(-490_000L);
        assertThat(movimientos("LIBERACION")).containsExactly(10_000L);
        Posicion posicion = posiciones.buscar(cuenta.id(), "ANDX").orElseThrow();
        assertThat(posicion.cantidad()).isEqualTo(100);
        assertThat(posicion.costoPromedio()).isEqualByComparingTo("4900");
        cuadra();
    }

    @Test
    void enCadaEjecucionParcialSeLiberaLoPagadoDeMenosYAlCancelarseElResto() {
        SolicitudReserva compra = compraLimite(5_000, 100);
        reservas.procesar(compra);

        liquidacion.aplicar(evento(compra, EstadoOrden.PARCIAL, 40, ejecucion(4_900, 40)));
        // Pagó 196.000 y liberó 4.000: lo reservado es justo lo que falta, 60 a 5.000
        assertThat(cuenta().saldoReservado()).isEqualTo(300_000);
        assertThat(reserva(compra.ordenId())).isEqualTo(new FilaReserva(500_000, 196_000, 4_000, "ACTIVA"));

        liquidacion.aplicar(evento(compra, EstadoOrden.PARCIAL, 70, ejecucion(5_000, 30)));
        liquidacion.aplicar(evento(compra, EstadoOrden.CANCELADA, 70, null));

        // 196.000 + 150.000 pagados; 4.000 + 150.000 (las 30 que no se compraron) liberados
        assertThat(cuenta().saldoReservado()).isZero();
        assertThat(cuenta().saldoDisponible()).isEqualTo(SALDO_INICIAL - 346_000);
        assertThat(reserva(compra.ordenId())).isEqualTo(new FilaReserva(500_000, 346_000, 154_000, "CONSUMIDA"));
        assertThat(movimientos("LIBERACION")).containsExactlyInAnyOrder(4_000L, 150_000L);
        // (40 × 4.900 + 30 × 5.000) / 70 = 4.942,857...
        assertThat(posiciones.buscar(cuenta().id(), "ANDX").orElseThrow().costoPromedio())
                .isEqualByComparingTo("4942.8571");
        cuadra();
    }

    @Test
    void unaOrdenCanceladaSinEjecutarNadaDevuelveTodoYSuReservaQuedaLiberada() {
        SolicitudReserva compra = compraLimite(5_000, 100);
        reservas.procesar(compra);

        liquidacion.aplicar(evento(compra, EstadoOrden.ABIERTA, 0, null));
        assertThat(cuenta().saldoReservado()).isEqualTo(500_000);
        liquidacion.aplicar(evento(compra, EstadoOrden.CANCELADA, 0, null));

        assertThat(cuenta().saldoDisponible()).isEqualTo(SALDO_INICIAL);
        assertThat(reserva(compra.ordenId())).isEqualTo(new FilaReserva(500_000, 0, 500_000, "LIBERADA"));
        cuadra();
    }

    @Test
    void unaCompraAMercadoLiberaLoQueSobraDelDiezPorCientoAlTerminar() {
        SolicitudReserva compra = new SolicitudReserva(UUID.randomUUID(), UUID.randomUUID(), ana.toString(), "ANDX",
                Lado.COMPRA, TipoOrden.MERCADO, null, 10, 5_000, Instant.now());
        reservas.procesar(compra);

        liquidacion.aplicar(evento(compra, EstadoOrden.EJECUTADA, 10, ejecucion(5_100, 10)));

        // Reservó 55.000, pagó 51.000 y liberó 4.000
        assertThat(cuenta().saldoDisponible()).isEqualTo(SALDO_INICIAL - 51_000);
        assertThat(reserva(compra.ordenId())).isEqualTo(new FilaReserva(55_000, 51_000, 4_000, "CONSUMIDA"));
        cuadra();
    }

    @Test
    void unEventoRepetidoSeLiquidaUnaSolaVez() {
        SolicitudReserva compra = compraLimite(5_000, 100);
        reservas.procesar(compra);
        EventoOrden parcial = evento(compra, EstadoOrden.PARCIAL, 40, ejecucion(4_900, 40));

        assertThat(liquidacion.aplicar(parcial)).isTrue();
        assertThat(liquidacion.aplicar(parcial)).isFalse();

        assertThat(cuenta().saldoReservado()).isEqualTo(300_000);
        assertThat(movimientos("COMPRA")).hasSize(1);
        assertThat(posiciones.buscar(cuenta().id(), "ANDX").orElseThrow().cantidad()).isEqualTo(40);
    }

    @Test
    void unaEjecucionPorEncimaDelLimiteNoSeLiquidaYNoQuedaNadaAMedias() {
        SolicitudReserva compra = compraLimite(5_000, 100);
        reservas.procesar(compra);
        EventoOrden imposible = evento(compra, EstadoOrden.EJECUTADA, 100, ejecucion(5_100, 100));

        assertThatThrownBy(() -> liquidacion.aplicar(imposible))
                .isInstanceOf(EventoImposibleException.class)
                .hasMessage("se ejecutó a 5100 COP, por encima del límite de 5000");

        // Ni el saldo, ni la posición, ni la marca del evento
        assertThat(cuenta().saldoReservado()).isEqualTo(500_000);
        assertThat(posiciones.buscar(cuenta().id(), "ANDX")).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM evento_procesado WHERE id_evento = :id")
                .param("id", imposible.eventoId()).query(Integer.class).single()).isZero();
    }

    @Test
    void unEventoDeOtroUsuarioOSobreUnaReservaTerminadaNoSeLiquida() {
        SolicitudReserva compra = compraLimite(5_000, 100);
        reservas.procesar(compra);
        EventoOrden deOtro = new EventoOrden(UUID.randomUUID(), compra.ordenId(), UUID.randomUUID().toString(), "ANDX",
                EstadoOrden.CANCELADA, 100, 0, null, null, Instant.now());

        assertThatThrownBy(() -> liquidacion.aplicar(deOtro)).isInstanceOf(EventoImposibleException.class);

        liquidacion.aplicar(evento(compra, EstadoOrden.CANCELADA, 0, null));
        assertThatThrownBy(() -> liquidacion.aplicar(evento(compra, EstadoOrden.PARCIAL, 10, ejecucion(5_000, 10))))
                .isInstanceOf(EventoImposibleException.class)
                .hasMessage("la reserva ya terminó (LIBERADA)");
        assertThat(cuenta().saldoDisponible()).isEqualTo(SALDO_INICIAL);
    }

    @Test
    void unaOrdenSinReservaNoSeLiquida() {
        // Una venta, o una compra de antes de las reservas
        EventoOrden sinReserva = new EventoOrden(UUID.randomUUID(), UUID.randomUUID(), ana.toString(), "ANDX",
                EstadoOrden.EJECUTADA, 100, 100, ejecucion(5_000, 100), null, Instant.now());

        assertThat(liquidacion.aplicar(sinReserva)).isFalse();

        assertThat(cuenta().saldoDisponible()).isEqualTo(SALDO_INICIAL);
    }

    /**
     * Regla 4 y RNF-08: el reservado es la suma de las reservas ACTIVAS, y el total de la cuenta es el saldo inicial
     * más las compras (negativas). Las reservas y liberaciones solo mueven dinero de un lado al otro.
     */
    private void cuadra() {
        Cuenta cuenta = cuenta();
        long activas = jdbc.sql("""
                        SELECT COALESCE(SUM(monto_reservado - monto_consumido - monto_liberado), 0) FROM reserva
                        WHERE cuenta_id = :cuenta AND estado = 'ACTIVA'""")
                .param("cuenta", cuenta.id()).query(Long.class).single();
        long compras = movimientos("COMPRA").stream().mapToLong(Long::longValue).sum();
        long reservasYLiberaciones = movimientos("RESERVA").stream().mapToLong(Long::longValue).sum()
                + movimientos("LIBERACION").stream().mapToLong(Long::longValue).sum();
        assertThat(cuenta.saldoReservado()).isEqualTo(activas);
        assertThat(cuenta.saldoTotal()).isEqualTo(SALDO_INICIAL + compras);
        assertThat(cuenta.saldoDisponible()).isEqualTo(SALDO_INICIAL + reservasYLiberaciones);
    }

    private SolicitudReserva compraLimite(long precio, int cantidad) {
        return new SolicitudReserva(UUID.randomUUID(), UUID.randomUUID(), ana.toString(), "ANDX", Lado.COMPRA,
                TipoOrden.LIMITE, precio, cantidad, 5_000, Instant.now());
    }

    private EventoOrden evento(SolicitudReserva compra, EstadoOrden estado, int ejecutada, EjecucionOrden ejecucion) {
        return new EventoOrden(UUID.randomUUID(), compra.ordenId(), ana.toString(), "ANDX", estado, compra.cantidad(),
                ejecutada, ejecucion, null, Instant.now());
    }

    private static EjecucionOrden ejecucion(long precio, int cantidad) {
        return new EjecucionOrden(UUID.randomUUID(), precio, cantidad);
    }

    /** Deja la cuenta de Ana con ese disponible, como si ya hubiera gastado el resto. */
    private void conDisponible(long disponible) {
        jdbc.sql("UPDATE cuenta SET saldo_disponible = :disponible WHERE usuario_id = :id")
                .param("disponible", disponible).param("id", ana).update();
    }

    private Cuenta cuenta() {
        return cuentas.buscarPorUsuario(ana).orElseThrow();
    }

    private FilaReserva reserva(UUID ordenId) {
        return jdbc.sql("""
                        SELECT monto_reservado, monto_consumido, monto_liberado, estado FROM reserva
                        WHERE orden_id = :orden""")
                .param("orden", ordenId).query(FilaReserva.class).single();
    }

    private int reservasDeLaCuenta() {
        return jdbc.sql("SELECT count(*) FROM reserva WHERE cuenta_id = :cuenta")
                .param("cuenta", cuenta().id()).query(Integer.class).single();
    }

    private List<Long> movimientos(String tipo) {
        return jdbc.sql("SELECT monto FROM movimiento WHERE cuenta_id = :cuenta AND tipo = :tipo ORDER BY creado_en")
                .param("cuenta", cuenta().id()).param("tipo", tipo).query(Long.class).list();
    }

    /** La respuesta que quedó en el outbox para reservations.events. */
    private Respuesta respuestaDe(SolicitudReserva solicitud) {
        return jdbc.sql("""
                        SELECT clave, payload ->> 'resultado' AS resultado,
                               CAST(payload ->> 'montoReservado' AS bigint) AS monto, payload ->> 'motivo' AS motivo
                        FROM outbox WHERE topico = :topico AND payload ->> 'ordenId' = :orden""")
                .param("topico", Topicos.RESERVATIONS_EVENTS).param("orden", solicitud.ordenId().toString())
                .query(Respuesta.class).single();
    }

    private int respuestasDe(SolicitudReserva solicitud) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE payload ->> 'ordenId' = :orden")
                .param("orden", solicitud.ordenId().toString()).query(Integer.class).single();
    }

    record FilaReserva(long montoReservado, long montoConsumido, long montoLiberado, String estado) {
    }

    record Respuesta(String clave, String resultado, long monto, String motivo) {
    }
}
