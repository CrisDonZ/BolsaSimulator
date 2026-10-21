package co.bolsasim.portfolio.servicio;

import static org.assertj.core.api.Assertions.assertThat;

import co.bolsasim.eventos.EjecucionOrden;
import co.bolsasim.eventos.EstadoInstrumento;
import co.bolsasim.eventos.EstadoOrden;
import co.bolsasim.eventos.EventoOrden;
import co.bolsasim.eventos.InstrumentoActualizado;
import co.bolsasim.eventos.Lado;
import co.bolsasim.eventos.SolicitudReserva;
import co.bolsasim.eventos.TipoOrden;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Prueba de conservación (RNF-08) contra PostgreSQL real: el creador de mercado recibe la oferta inicial y opera con
 * dos inversionistas, con ejecuciones parciales, órdenes a mercado y cancelaciones. Cada operación del motor llega
 * como dos eventos, el del comprador y el del vendedor, como en orders.events. Al final el dinero de todas las
 * cuentas es el mismo del principio y las acciones del símbolo suman sus acciones emitidas.
 *
 * <p>Necesita Docker. Si Docker está cerrado, la prueba se salta en vez de fallar.
 */
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "bolsasim.outbox.programado=false"})
@Testcontainers(disabledWithoutDocker = true)
class ConservacionIntegracionTest {

    private static final String SIMBOLO = "CONS";
    private static final long EMITIDAS = 10_000;

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
    private JdbcClient jdbc;

    private final UUID creador = UUID.randomUUID();
    private final UUID ana = UUID.randomUUID();
    private final UUID beto = UUID.randomUUID();

    @Test
    void despuesDeComprasVentasParcialesYCancelacionesNoAparecenNiDesaparecenDineroNiAcciones() {
        servicioCuentas.abrir(creador, "BOT");
        emisiones.registrarCreador(creador);
        servicioCuentas.abrir(ana, "INVERSIONISTA");
        servicioCuentas.abrir(beto, "INVERSIONISTA");
        emisiones.emitir(new InstrumentoActualizado(SIMBOLO, "Conservación S.A.", "Pruebas", 5_000, 10, EMITIDAS,
                EstadoInstrumento.ACTIVO, Instant.now()));
        long dineroAlEmpezar = dineroTotal();
        assertThat(dineroAlEmpezar).isEqualTo(30_000_000);
        assertThat(accionesTotales()).isEqualTo(EMITIDAS);

        // 1. El creador vende 1.000 a 5.000; Ana compra 600 a 5.100 y se ejecuta a 5.000 (el precio que esperaba)
        SolicitudReserva ventaCreador = orden(creador, Lado.VENTA, TipoOrden.LIMITE, 5_000L, 1_000);
        SolicitudReserva compraAna = orden(ana, Lado.COMPRA, TipoOrden.LIMITE, 5_100L, 600);
        operar(compraAna, EstadoOrden.EJECUTADA, 600, ventaCreador, EstadoOrden.PARCIAL, 600, 5_000, 600);

        // 2. Beto compra 500 a 5.000: le tocan las 400 que le quedan al creador, y cancela el resto
        SolicitudReserva compraBeto = orden(beto, Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 500);
        operar(compraBeto, EstadoOrden.PARCIAL, 400, ventaCreador, EstadoOrden.EJECUTADA, 1_000, 5_000, 400);
        terminar(compraBeto, EstadoOrden.CANCELADA, 400);

        // 3. Ana vende 300 a 4.900; Beto compra 200 a mercado. Ana cancela las 100 que no se vendieron
        SolicitudReserva ventaAna = orden(ana, Lado.VENTA, TipoOrden.LIMITE, 4_900L, 300);
        SolicitudReserva mercadoBeto = orden(beto, Lado.COMPRA, TipoOrden.MERCADO, null, 200);
        operar(mercadoBeto, EstadoOrden.EJECUTADA, 200, ventaAna, EstadoOrden.PARCIAL, 200, 4_900, 200);
        terminar(ventaAna, EstadoOrden.CANCELADA, 200);

        // 4. Ana deja una compra de 50 a 5.000 en el libro; el creador vende 50 a mercado contra ella
        SolicitudReserva compraAbierta = orden(ana, Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 50);
        SolicitudReserva mercadoCreador = orden(creador, Lado.VENTA, TipoOrden.MERCADO, null, 50);
        liquidacion.aplicar(evento(compraAbierta, EstadoOrden.ABIERTA, 0, null));
        operar(compraAbierta, EstadoOrden.EJECUTADA, 50, mercadoCreador, EstadoOrden.EJECUTADA, 50, 5_000, 50);

        // 5. Beto deja una venta de 100 que nunca se ejecuta y la cancela: las acciones vuelven a estar libres
        SolicitudReserva ventaBeto = orden(beto, Lado.VENTA, TipoOrden.LIMITE, 5_400L, 100);
        terminar(ventaBeto, EstadoOrden.CANCELADA, 0);

        // Nada se creó ni se perdió, y nada quedó bloqueado: todas las órdenes terminaron
        assertThat(dineroTotal()).isEqualTo(dineroAlEmpezar);
        assertThat(accionesTotales()).isEqualTo(EMITIDAS);
        assertThat(jdbc.sql("SELECT COALESCE(SUM(saldo_reservado), 0) FROM cuenta").query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("SELECT COALESCE(SUM(cantidad_reservada), 0) FROM posicion").query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM reserva WHERE estado = 'ACTIVA'").query(Integer.class).single())
                .isZero();

        // Y cada uno tiene lo que debe: Ana compró 650 y vendió 200; Beto compró 600; el creador vendió 1.050
        assertThat(acciones(ana)).isEqualTo(450);
        assertThat(acciones(beto)).isEqualTo(600);
        assertThat(acciones(creador)).isEqualTo(EMITIDAS - 1_050);
        // Ana: −600 × 5.000 + 200 × 4.900 − 50 × 5.000; Beto: −400 × 5.000 − 200 × 4.900
        assertThat(dinero(ana)).isEqualTo(10_000_000 - 3_000_000 + 980_000 - 250_000);
        assertThat(dinero(beto)).isEqualTo(10_000_000 - 2_000_000 - 980_000);
        assertThat(dinero(creador)).isEqualTo(10_000_000 + 5_000_000 + 250_000);
    }

    /**
     * Una operación como la publica el motor: un evento para cada orden, con la misma ejecución. Primero reserva las
     * dos órdenes, si es su primera operación, como haría la saga antes de que lleguen al motor.
     */
    private void operar(SolicitudReserva compra, EstadoOrden estadoCompra, int ejecutadaCompra,
                        SolicitudReserva venta, EstadoOrden estadoVenta, int ejecutadaVenta, long precio, int cantidad) {
        reservas.procesar(compra);
        reservas.procesar(venta);
        EjecucionOrden ejecucion = new EjecucionOrden(UUID.randomUUID(), precio, cantidad);
        liquidacion.aplicar(evento(venta, estadoVenta, ejecutadaVenta, ejecucion));
        liquidacion.aplicar(evento(compra, estadoCompra, ejecutadaCompra, ejecucion));
    }

    /** La orden termina sin otra ejecución: se cancela, con lo que alcanzó a ejecutar. */
    private void terminar(SolicitudReserva orden, EstadoOrden estado, int ejecutada) {
        reservas.procesar(orden);
        liquidacion.aplicar(evento(orden, estado, ejecutada, null));
    }

    private static SolicitudReserva orden(UUID usuario, Lado lado, TipoOrden tipo, Long precio, int cantidad) {
        return new SolicitudReserva(UUID.randomUUID(), UUID.randomUUID(), usuario.toString(), SIMBOLO, lado, tipo,
                precio, cantidad, 5_000, Instant.now());
    }

    private static EventoOrden evento(SolicitudReserva orden, EstadoOrden estado, int ejecutada,
                                      EjecucionOrden ejecucion) {
        return new EventoOrden(UUID.randomUUID(), orden.ordenId(), orden.usuarioId(), SIMBOLO, estado,
                orden.cantidad(), ejecutada, ejecucion, null, Instant.now());
    }

    /** RNF-08: todo el dinero del sistema, disponible y reservado. */
    private long dineroTotal() {
        return jdbc.sql("SELECT SUM(saldo_disponible + saldo_reservado) FROM cuenta").query(Long.class).single();
    }

    private long accionesTotales() {
        return jdbc.sql("SELECT COALESCE(SUM(cantidad), 0) FROM posicion WHERE simbolo = :simbolo")
                .param("simbolo", SIMBOLO).query(Long.class).single();
    }

    private long dinero(UUID usuario) {
        return jdbc.sql("SELECT saldo_disponible + saldo_reservado FROM cuenta WHERE usuario_id = :usuario")
                .param("usuario", usuario).query(Long.class).single();
    }

    private long acciones(UUID usuario) {
        return jdbc.sql("""
                        SELECT p.cantidad FROM posicion p JOIN cuenta c ON c.id = p.cuenta_id
                        WHERE c.usuario_id = :usuario AND p.simbolo = :simbolo""")
                .param("usuario", usuario).param("simbolo", SIMBOLO).query(Long.class).single();
    }
}
