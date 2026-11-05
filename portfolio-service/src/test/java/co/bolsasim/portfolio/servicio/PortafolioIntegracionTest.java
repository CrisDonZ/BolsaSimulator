package co.bolsasim.portfolio.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.bolsasim.eventos.EjecucionOrden;
import co.bolsasim.eventos.EstadoInstrumento;
import co.bolsasim.eventos.EstadoOrden;
import co.bolsasim.eventos.EventoOrden;
import co.bolsasim.eventos.InstrumentoActualizado;
import co.bolsasim.eventos.Lado;
import co.bolsasim.eventos.SolicitudReserva;
import co.bolsasim.eventos.TipoOrden;
import co.bolsasim.portfolio.TokensDePrueba;
import co.bolsasim.portfolio.cuenta.RepositorioCuentas;
import co.bolsasim.portfolio.precio.RepositorioPrecios;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Prueba de integración del portafolio contra PostgreSQL real (RNF-36), con los criterios de HU-11: cada posición
 * con su precio promedio, su valor actual y su ganancia o pérdida, y el valor total. Las compras se reservan y se
 * liquidan llamando a los servicios, como lo hacen los listeners; el último precio se guarda como lo hace el de
 * trades.executed. Kafka no hace falta.
 *
 * <p>Cada prueba usa un símbolo nuevo, emitido a 5.000 para el creador de mercado, y un usuario nuevo.
 *
 * <p>Necesita Docker. Si Docker está cerrado, la prueba se salta en vez de fallar.
 */
@SpringBootTest(properties = {"spring.kafka.listener.auto-startup=false", "bolsasim.outbox.programado=false"})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PortafolioIntegracionTest {

    private static final UUID CREADOR = UUID.randomUUID();
    private static final AtomicInteger SIMBOLOS = new AtomicInteger();

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void jwksDePrueba(DynamicPropertyRegistry propiedades) {
        TokensDePrueba.configurar(propiedades);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ServicioPortafolio portafolio;

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
    private RepositorioPrecios precios;

    private String simbolo;
    private UUID ana;

    @BeforeEach
    void unSimboloNuevoEmitidoA5000YAnaConSuCuentaNueva() {
        if (cuentas.creadorDeMercado().isEmpty()) {
            servicioCuentas.abrir(CREADOR, "BOT");
            emisiones.registrarCreador(CREADOR);
        }
        simbolo = "P" + (char) ('A' + SIMBOLOS.getAndIncrement());
        emisiones.emitir(new InstrumentoActualizado(simbolo, "Empresa " + simbolo, "Pruebas", 5_000, 10, 1_000,
                EstadoInstrumento.ACTIVO, Instant.now()));
        ana = UUID.randomUUID();
        servicioCuentas.abrir(ana, "INVERSIONISTA");
    }

    @Test
    void compro100A5000Y100A6000ElPrecioPromedioEs5500() {
        comprar(100, 5_000);
        comprar(100, 6_000);
        precios.actualizar(simbolo, 6_000, Instant.now());

        Portafolio suyo = portafolio.consultar(ana, "INVERSIONISTA");

        assertThat(suyo.posiciones()).hasSize(1);
        PosicionValorada posicion = suyo.posiciones().getFirst();
        assertThat(posicion.simbolo()).isEqualTo(simbolo);
        assertThat(posicion.cantidad()).isEqualTo(200);
        assertThat(posicion.precioPromedio()).isEqualByComparingTo("5500");
        assertThat(posicion.precioActual()).isEqualTo(6_000);
        assertThat(posicion.valorActual()).isEqualTo(1_200_000);
        assertThat(posicion.ganancia()).isEqualTo(100_000);
        assertThat(posicion.gananciaPorcentaje()).isEqualByComparingTo("9.09");
        // Pagó 1.100.000 de sus 10.000.000, y las acciones valen 1.200.000
        assertThat(suyo.cuenta().saldoTotal()).isEqualTo(8_900_000);
        assertThat(suyo.valorTotal()).isEqualTo(10_100_000);
    }

    @Test
    void sinOperacionesDelSimboloSeValoraConSuPrecioInicial() {
        comprar(10, 5_200);

        PosicionValorada posicion = portafolio.consultar(ana, "INVERSIONISTA").posiciones().getFirst();

        assertThat(posicion.precioActual()).isEqualTo(5_000);
        assertThat(posicion.ganancia()).isEqualTo(-2_000);
        assertThat(posicion.gananciaPorcentaje()).isEqualByComparingTo("-3.85");
    }

    @Test
    void unaOperacionMasViejaNoReemplazaElUltimoPrecio() {
        comprar(10, 5_000);
        Instant ahora = Instant.now().truncatedTo(ChronoUnit.MICROS);

        assertThat(precios.actualizar(simbolo, 5_100, ahora)).isTrue();
        assertThat(precios.actualizar(simbolo, 4_900, ahora.minusSeconds(1))).isFalse();
        // La misma operación otra vez deja lo mismo
        assertThat(precios.actualizar(simbolo, 5_100, ahora)).isTrue();

        assertThat(portafolio.consultar(ana, "INVERSIONISTA").posiciones().getFirst().precioActual())
                .isEqualTo(5_100);
    }

    @Test
    void sinAccionesElPortafolioEsSoloElSaldo() {
        Portafolio suyo = portafolio.consultar(ana, "INVERSIONISTA");

        assertThat(suyo.posiciones()).isEmpty();
        assertThat(suyo.valorAcciones()).isZero();
        assertThat(suyo.valorTotal()).isEqualTo(10_000_000);
    }

    @Test
    void unaPosicionQueSeVendioCompletaNoAparece() {
        comprar(10, 5_000);
        SolicitudReserva venta = new SolicitudReserva(UUID.randomUUID(), UUID.randomUUID(), ana.toString(), simbolo,
                Lado.VENTA, TipoOrden.LIMITE, 5_000L, 10, 5_000, Instant.now());
        reservas.procesar(venta);
        liquidacion.aplicar(new EventoOrden(UUID.randomUUID(), venta.ordenId(), ana.toString(), simbolo,
                EstadoOrden.EJECUTADA, 10, 10, new EjecucionOrden(UUID.randomUUID(), 5_000, 10), null, Instant.now()));

        Portafolio suyo = portafolio.consultar(ana, "INVERSIONISTA");

        assertThat(suyo.posiciones()).isEmpty();
        assertThat(suyo.valorTotal()).isEqualTo(10_000_000);
    }

    @Test
    void cadaUsuarioVeSoloSuPortafolio() throws Exception {
        comprar(100, 5_000);
        UUID beto = UUID.randomUUID();

        // La ruta no lleva id: con el token de Beto se ve el portafolio de Beto, nunca el de Ana
        mvc.perform(get("/api/portafolio")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TokensDePrueba.de(beto, "INVERSIONISTA")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posiciones.length()").value(0))
                .andExpect(jsonPath("$.valorTotal").value(10_000_000));
        mvc.perform(get("/api/portafolio")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TokensDePrueba.de(ana, "INVERSIONISTA")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posiciones.length()").value(1))
                .andExpect(jsonPath("$.posiciones[0].simbolo").value(simbolo))
                .andExpect(jsonPath("$.posiciones[0].cantidad").value(100))
                .andExpect(jsonPath("$.saldoTotal").value(9_500_000))
                .andExpect(jsonPath("$.valorAcciones").value(500_000))
                .andExpect(jsonPath("$.valorTotal").value(10_000_000));
    }

    @Test
    void unAdminRecibe404() throws Exception {
        mvc.perform(get("/api/portafolio").header(HttpHeaders.AUTHORIZATION, "Bearer " + TokensDePrueba.valido("ADMIN")))
                .andExpect(status().isNotFound());
    }

    /** Una compra límite de Ana que el motor ejecuta completa a su precio. */
    private void comprar(int cantidad, long precio) {
        SolicitudReserva compra = new SolicitudReserva(UUID.randomUUID(), UUID.randomUUID(), ana.toString(), simbolo,
                Lado.COMPRA, TipoOrden.LIMITE, precio, cantidad, 5_000, Instant.now());
        assertThat(reservas.procesar(compra)).isTrue();
        assertThat(liquidacion.aplicar(new EventoOrden(UUID.randomUUID(), compra.ordenId(), ana.toString(), simbolo,
                EstadoOrden.EJECUTADA, cantidad, cantidad, new EjecucionOrden(UUID.randomUUID(), precio, cantidad),
                null, Instant.now()))).isTrue();
    }
}
