package co.bolsasim.portfolio.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.bolsasim.eventos.EjecucionOrden;
import co.bolsasim.eventos.EstadoInstrumento;
import co.bolsasim.eventos.EstadoOrden;
import co.bolsasim.eventos.EventoOrden;
import co.bolsasim.eventos.InstrumentoActualizado;
import co.bolsasim.eventos.Lado;
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
import java.util.concurrent.atomic.AtomicInteger;
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
 * Prueba de integración de la oferta inicial y las ventas contra PostgreSQL real (RNF-36), con los criterios de
 * HU-06: el creador de mercado recibe las acciones emitidas una sola vez por símbolo, una venta reserva acciones o
 * se rechaza, y la liquidación entrega las acciones, cobra el dinero y libera lo que no se vendió. Los eventos se
 * aplican llamando a los servicios directamente, como lo hacen los listeners; Kafka no hace falta.
 *
 * <p>Cada prueba usa un símbolo nuevo; el creador de mercado es uno solo para toda la clase, como en producción.
 *
 * <p>Necesita Docker. Si Docker está cerrado, la prueba se salta en vez de fallar.
 */
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "bolsasim.outbox.programado=false"})
@Testcontainers(disabledWithoutDocker = true)
class VentasYEmisionesIntegracionTest {

    private static final long SALDO_INICIAL = 10_000_000;
    private static final UUID CREADOR = UUID.randomUUID();
    private static final AtomicInteger SIMBOLOS = new AtomicInteger();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private ServicioReservas reservas;

    @Autowired
    private ServicioLiquidacion liquidacion;

    @Autowired
    private ServicioEmisiones emisiones;

    @Autowired
    private ServicioCuentas servicioCuentas;

    @Autowired
    private RepositorioCuentas cuentas;

    @Autowired
    private RepositorioPosiciones posiciones;

    @Autowired
    private JdbcClient jdbc;

    private String simbolo;

    /** El creador de mercado existe y recibió 1.000 acciones a 5.000 de un símbolo nuevo. */
    @BeforeEach
    void elCreadorDeMercadoTieneLaOfertaInicialDeUnSimboloNuevo() {
        if (cuentas.creadorDeMercado().isEmpty()) {
            servicioCuentas.abrir(CREADOR, "BOT");
            emisiones.registrarCreador(CREADOR);
        }
        simbolo = "V" + (char) ('A' + SIMBOLOS.getAndIncrement());
        assertThat(emisiones.emitir(instrumento(simbolo, EstadoInstrumento.ACTIVO))).isTrue();
    }

    @Test
    void laOfertaInicialLlegaAlCreadorConElPrecioInicialComoCostoYUnMovimientoEmision() {
        Posicion posicion = posicionDe(CREADOR);
        assertThat(posicion.cantidad()).isEqualTo(1_000);
        assertThat(posicion.cantidadReservada()).isZero();
        assertThat(posicion.costoPromedio()).isEqualByComparingTo("5000");
        assertThat(jdbc.sql("""
                        SELECT monto, cantidad FROM movimiento
                        WHERE cuenta_id = :cuenta AND tipo = 'EMISION' AND simbolo = :simbolo""")
                .param("cuenta", cuentaDe(CREADOR).id()).param("simbolo", simbolo)
                .query((fila, n) -> fila.getLong("monto") + "/" + fila.getLong("cantidad")).list())
                .containsExactly("0/1000");
        // Ni un peso: la emisión no mueve dinero. El creador solo tiene el inicial y lo que vendió en otras pruebas
        assertThat(cuentaDe(CREADOR).saldoTotal()).isEqualTo(saldoAntes());
    }

    @Test
    void unaEdicionUnaSuspensionOElMismoEventoNoEmitenDeNuevo() {
        assertThat(emisiones.emitir(instrumento(simbolo, EstadoInstrumento.ACTIVO))).isFalse();
        assertThat(emisiones.emitir(instrumento(simbolo, EstadoInstrumento.SUSPENDIDO))).isFalse();

        assertThat(posicionDe(CREADOR).cantidad()).isEqualTo(1_000);
        assertThat(totalDeAcciones()).isEqualTo(1_000);
    }

    @Test
    void unSegundoCreadorDeMercadoNoRecibeNada() {
        UUID otro = UUID.randomUUID();
        servicioCuentas.abrir(otro, "BOT");

        assertThat(emisiones.registrarCreador(otro)).isFalse();

        assertThat(cuentas.creadorDeMercado()).contains(cuentaDe(CREADOR).id());
        assertThat(posiciones.buscar(cuentaDe(otro).id(), simbolo)).isEmpty();
    }

    @Test
    void unaVentaReservaLasAccionesYLasQuedanLibresSiguenDisponibles() {
        SolicitudReserva venta = venta(CREADOR, TipoOrden.LIMITE, 600);

        assertThat(reservas.procesar(venta)).isTrue();

        Posicion posicion = posicionDe(CREADOR);
        assertThat(posicion.cantidad()).isEqualTo(1_000);
        assertThat(posicion.cantidadReservada()).isEqualTo(600);
        assertThat(posicion.libres()).isEqualTo(400);
        assertThat(reserva(venta)).isEqualTo(new FilaReserva("ACCIONES", simbolo, 600, 0, 0, "ACTIVA"));
        assertThat(respuesta(venta)).isEqualTo("ACEPTADA/600/null");
        // Reservar acciones no toca el dinero
        assertThat(cuentaDe(CREADOR).saldoReservado()).isZero();
    }

    @Test
    void unaVentaDeMasAccionesDeLasLibresSeRechazaConElMotivoYNoCambiaNada() {
        reservas.procesar(venta(CREADOR, TipoOrden.LIMITE, 600));
        SolicitudReserva deMas = venta(CREADOR, TipoOrden.MERCADO, 500);

        reservas.procesar(deMas);

        assertThat(respuesta(deMas))
                .isEqualTo("RECHAZADA/0/Acciones insuficientes: tienes 400 de " + simbolo
                        + " disponibles y la orden vende 500.");
        assertThat(posicionDe(CREADOR).cantidadReservada()).isEqualTo(600);
        assertThat(reservaExiste(deMas)).isFalse();
    }

    @Test
    void quienNoTieneAccionesDeEseSimboloNoPuedeVenderlas() {
        UUID ana = UUID.randomUUID();
        SolicitudReserva venta = venta(ana, TipoOrden.LIMITE, 1);

        reservas.procesar(venta);

        assertThat(respuesta(venta)).isEqualTo("RECHAZADA/0/Acciones insuficientes: tienes 0 de " + simbolo
                + " disponibles y la orden vende 1.");
    }

    @Test
    void unaVentaEjecutadaEnDosPartesEntregaLasAccionesYCobraCadaParteASuPrecio() {
        SolicitudReserva venta = venta(CREADOR, TipoOrden.LIMITE, 100);
        reservas.procesar(venta);

        liquidacion.aplicar(evento(venta, EstadoOrden.PARCIAL, 40, ejecucion(5_100, 40)));
        Posicion parcial = posicionDe(CREADOR);
        assertThat(parcial.cantidad()).isEqualTo(960);
        assertThat(parcial.cantidadReservada()).isEqualTo(60);

        liquidacion.aplicar(evento(venta, EstadoOrden.EJECUTADA, 100, ejecucion(5_000, 60)));

        Posicion posicion = posicionDe(CREADOR);
        assertThat(posicion.cantidad()).isEqualTo(900);
        assertThat(posicion.cantidadReservada()).isZero();
        // Vender no cambia lo que costaron las que quedan
        assertThat(posicion.costoPromedio()).isEqualByComparingTo("5000");
        // 40 × 5.100 + 60 × 5.000
        assertThat(cuentaDe(CREADOR).saldoDisponible()).isEqualTo(saldoAntes() + 504_000);
        assertThat(ventas()).containsExactlyInAnyOrder(204_000L, 300_000L);
        assertThat(reserva(venta)).isEqualTo(new FilaReserva("ACCIONES", simbolo, 100, 100, 0, "CONSUMIDA"));
    }

    @Test
    void alCancelarseLasAccionesQueNoSeVendieronVuelvenAEstarLibres() {
        SolicitudReserva venta = venta(CREADOR, TipoOrden.LIMITE, 100);
        reservas.procesar(venta);

        liquidacion.aplicar(evento(venta, EstadoOrden.PARCIAL, 30, ejecucion(5_000, 30)));
        liquidacion.aplicar(evento(venta, EstadoOrden.CANCELADA, 30, null));

        Posicion posicion = posicionDe(CREADOR);
        assertThat(posicion.cantidad()).isEqualTo(970);
        assertThat(posicion.cantidadReservada()).isZero();
        assertThat(posicion.libres()).isEqualTo(970);
        assertThat(reserva(venta)).isEqualTo(new FilaReserva("ACCIONES", simbolo, 100, 30, 70, "CONSUMIDA"));
    }

    @Test
    void unaVentaCanceladaSinVenderNadaDevuelveTodasYSuReservaQuedaLiberada() {
        SolicitudReserva venta = venta(CREADOR, TipoOrden.MERCADO, 100);
        reservas.procesar(venta);

        liquidacion.aplicar(evento(venta, EstadoOrden.CANCELADA, 0, null));

        assertThat(posicionDe(CREADOR).cantidadReservada()).isZero();
        assertThat(posicionDe(CREADOR).cantidad()).isEqualTo(1_000);
        assertThat(reserva(venta)).isEqualTo(new FilaReserva("ACCIONES", simbolo, 100, 0, 100, "LIBERADA"));
    }

    @Test
    void unEventoDeVentaRepetidoSeLiquidaUnaSolaVez() {
        SolicitudReserva venta = venta(CREADOR, TipoOrden.LIMITE, 100);
        reservas.procesar(venta);
        EventoOrden parcial = evento(venta, EstadoOrden.PARCIAL, 40, ejecucion(5_000, 40));
        long antes = cuentaDe(CREADOR).saldoDisponible();

        assertThat(liquidacion.aplicar(parcial)).isTrue();
        assertThat(liquidacion.aplicar(parcial)).isFalse();

        assertThat(posicionDe(CREADOR).cantidad()).isEqualTo(960);
        assertThat(cuentaDe(CREADOR).saldoDisponible()).isEqualTo(antes + 200_000);
        assertThat(ventas()).hasSize(1);
    }

    @Test
    void unaEjecucionDeMasAccionesDeLasReservadasNoSeLiquidaYNoQuedaNadaAMedias() {
        SolicitudReserva venta = venta(CREADOR, TipoOrden.LIMITE, 100);
        reservas.procesar(venta);
        EventoOrden imposible = evento(venta, EstadoOrden.EJECUTADA, 150, ejecucion(5_000, 150));
        long antes = cuentaDe(CREADOR).saldoDisponible();

        assertThatThrownBy(() -> liquidacion.aplicar(imposible))
                .isInstanceOf(EventoImposibleException.class)
                .hasMessage("se venderían 150 acciones y la reserva solo tiene 100");

        assertThat(posicionDe(CREADOR).cantidad()).isEqualTo(1_000);
        assertThat(posicionDe(CREADOR).cantidadReservada()).isEqualTo(100);
        assertThat(cuentaDe(CREADOR).saldoDisponible()).isEqualTo(antes);
        assertThat(jdbc.sql("SELECT count(*) FROM evento_procesado WHERE id_evento = :id")
                .param("id", imposible.eventoId()).query(Integer.class).single()).isZero();
    }

    @Test
    void dosVentasALaVezQueJuntasPasanDeLasLibresNoVendenDosVecesLasMismasAcciones() throws Exception {
        List<SolicitudReserva> ventas = List.of(venta(CREADOR, TipoOrden.LIMITE, 700),
                venta(CREADOR, TipoOrden.LIMITE, 700));
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch largada = new CountDownLatch(1);
            List<Future<?>> resultados = new ArrayList<>();
            for (SolicitudReserva venta : ventas) {
                resultados.add(hilos.submit(() -> {
                    largada.await();
                    return reservas.procesar(venta);
                }));
            }
            largada.countDown();
            for (Future<?> resultado : resultados) {
                resultado.get(30, TimeUnit.SECONDS);
            }
        } finally {
            hilos.shutdownNow();
        }

        assertThat(ventas.stream().map(venta -> respuesta(venta).split("/")[0]))
                .containsExactlyInAnyOrder("ACEPTADA", "RECHAZADA");
        assertThat(posicionDe(CREADOR).cantidadReservada()).isEqualTo(700);
    }

    @Test
    void unaReservaYUnaLiquidacionALaVezSobreLaMismaPosicionNoSePisan() throws Exception {
        // Una venta de 500 ya reservada se ejecuta mientras llega otra venta de 500: entre las dos son las 1.000
        SolicitudReserva primera = venta(CREADOR, TipoOrden.LIMITE, 500);
        reservas.procesar(primera);
        SolicitudReserva segunda = venta(CREADOR, TipoOrden.LIMITE, 500);
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch largada = new CountDownLatch(1);
            Future<?> liquidar = hilos.submit(() -> {
                largada.await();
                return liquidacion.aplicar(evento(primera, EstadoOrden.EJECUTADA, 500, ejecucion(5_000, 500)));
            });
            Future<?> reservar = hilos.submit(() -> {
                largada.await();
                return reservas.procesar(segunda);
            });
            largada.countDown();
            liquidar.get(30, TimeUnit.SECONDS);
            reservar.get(30, TimeUnit.SECONDS);
        } finally {
            hilos.shutdownNow();
        }

        // Pase lo que pase primero, la segunda alcanza y nada queda contado dos veces
        assertThat(respuesta(segunda)).startsWith("ACEPTADA");
        Posicion posicion = posicionDe(CREADOR);
        assertThat(posicion.cantidad()).isEqualTo(500);
        assertThat(posicion.cantidadReservada()).isEqualTo(500);
    }

    private SolicitudReserva venta(UUID usuario, TipoOrden tipo, int cantidad) {
        return new SolicitudReserva(UUID.randomUUID(), UUID.randomUUID(), usuario.toString(), simbolo, Lado.VENTA,
                tipo, tipo == TipoOrden.LIMITE ? 5_000L : null, cantidad, 5_000, Instant.now());
    }

    private EventoOrden evento(SolicitudReserva orden, EstadoOrden estado, int ejecutada, EjecucionOrden ejecucion) {
        return new EventoOrden(UUID.randomUUID(), orden.ordenId(), orden.usuarioId(), simbolo, estado,
                orden.cantidad(), ejecutada, ejecucion, null, Instant.now());
    }

    private static EjecucionOrden ejecucion(long precio, int cantidad) {
        return new EjecucionOrden(UUID.randomUUID(), precio, cantidad);
    }

    private static InstrumentoActualizado instrumento(String simbolo, EstadoInstrumento estado) {
        return new InstrumentoActualizado(simbolo, "Empresa " + simbolo, "Pruebas", 5_000, 10, 1_000, estado,
                Instant.now());
    }

    private Cuenta cuentaDe(UUID usuario) {
        return cuentas.buscarPorUsuario(usuario).orElseThrow();
    }

    private Posicion posicionDe(UUID usuario) {
        return posiciones.buscar(cuentaDe(usuario).id(), simbolo).orElseThrow();
    }

    /** El disponible del creador antes de las ventas de este símbolo: el inicial más lo que vendió en otras pruebas. */
    private long saldoAntes() {
        return SALDO_INICIAL + jdbc.sql("""
                        SELECT COALESCE(SUM(monto), 0) FROM movimiento
                        WHERE cuenta_id = :cuenta AND tipo = 'VENTA' AND simbolo <> :simbolo""")
                .param("cuenta", cuentaDe(CREADOR).id()).param("simbolo", simbolo)
                .query(Long.class).single();
    }

    private List<Long> ventas() {
        return jdbc.sql("SELECT monto FROM movimiento WHERE tipo = 'VENTA' AND simbolo = :simbolo")
                .param("simbolo", simbolo).query(Long.class).list();
    }

    private long totalDeAcciones() {
        return jdbc.sql("SELECT COALESCE(SUM(cantidad), 0) FROM posicion WHERE simbolo = :simbolo")
                .param("simbolo", simbolo).query(Long.class).single();
    }

    private FilaReserva reserva(SolicitudReserva orden) {
        return jdbc.sql("""
                        SELECT tipo, simbolo, monto_reservado, monto_consumido, monto_liberado, estado FROM reserva
                        WHERE orden_id = :orden""")
                .param("orden", orden.ordenId()).query(FilaReserva.class).single();
    }

    private boolean reservaExiste(SolicitudReserva orden) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM reserva WHERE orden_id = :orden)")
                .param("orden", orden.ordenId()).query(Boolean.class).single();
    }

    /** La respuesta del outbox para reservations.events, como "RESULTADO/monto/motivo". */
    private String respuesta(SolicitudReserva orden) {
        return jdbc.sql("""
                        SELECT payload ->> 'resultado' || '/' || (payload ->> 'montoReservado') || '/'
                               || COALESCE(payload ->> 'motivo', 'null')
                        FROM outbox WHERE topico = :topico AND payload ->> 'ordenId' = :orden""")
                .param("topico", Topicos.RESERVATIONS_EVENTS).param("orden", orden.ordenId().toString())
                .query(String.class).single();
    }

    record FilaReserva(String tipo, String simbolo, long montoReservado, long montoConsumido, long montoLiberado,
                       String estado) {
    }
}
