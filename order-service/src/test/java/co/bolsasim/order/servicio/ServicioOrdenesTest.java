package co.bolsasim.order.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.bolsasim.eventos.AccionComando;
import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.EstadoInstrumento;
import co.bolsasim.eventos.InstrumentoActualizado;
import co.bolsasim.eventos.Lado;
import co.bolsasim.eventos.TipoOrden;
import co.bolsasim.eventos.Topicos;
import co.bolsasim.order.instrumento.Instrumento;
import co.bolsasim.order.instrumento.RepositorioInstrumentos;
import co.bolsasim.order.orden.ConsultaOrdenes;
import co.bolsasim.order.orden.Cursor;
import co.bolsasim.order.orden.EstadoOrden;
import co.bolsasim.order.orden.FiltroEstado;
import co.bolsasim.order.orden.Orden;
import co.bolsasim.order.orden.RepositorioOrdenes;
import co.bolsasim.order.outbox.Outbox;
import co.bolsasim.order.usuario.RepositorioUsuarios;
import co.bolsasim.order.web.CrearOrdenRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Prueba unitaria: no levanta Spring, Kafka ni base de datos. Los repositorios, la consulta y el outbox son
 * "dobles" de Mockito, así comprobamos solo las reglas del servicio.
 */
class ServicioOrdenesTest {

    private static final UUID REGISTRADO = UUID.randomUUID();

    private final RepositorioOrdenes ordenes = mock(RepositorioOrdenes.class);
    private final ConsultaOrdenes consulta = mock(ConsultaOrdenes.class);
    private final Outbox outbox = mock(Outbox.class);
    private final RepositorioUsuarios usuarios = mock(RepositorioUsuarios.class);
    private final RepositorioInstrumentos instrumentos = mock(RepositorioInstrumentos.class);
    private final ServicioOrdenes servicio = new ServicioOrdenes(ordenes, consulta, outbox, usuarios, instrumentos);

    @BeforeEach
    void soloExistenUnUsuarioRegistradoYDosInstrumentosActivos() {
        when(usuarios.existsById(REGISTRADO)).thenReturn(true);
        when(instrumentos.findById("ANDX")).thenReturn(Optional.of(instrumento("ANDX", EstadoInstrumento.ACTIVO)));
        when(instrumentos.findById("CAFE")).thenReturn(Optional.of(instrumento("CAFE", EstadoInstrumento.ACTIVO)));
        when(ordenes.save(any())).thenAnswer(invocacion -> invocacion.getArgument(0));
    }

    @Test
    void unaOrdenValidaSeGuardaPendienteYSuComandoQuedaEnElOutbox() {
        Orden orden = servicio.crear(REGISTRADO.toString(), orden("andx", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100));

        assertThat(orden.getEstado()).isEqualTo(EstadoOrden.PENDIENTE);
        assertThat(orden.getUsuarioId()).isEqualTo(REGISTRADO);
        assertThat(orden.getSimbolo()).isEqualTo("ANDX");
        assertThat(orden.getCantidadEjecutada()).isZero();
        assertThat(orden.getPrecioPromedio()).isNull();

        // El comando es el de siempre, con la misma clave (el símbolo), y su ordenId es el id de la orden
        ComandoOrden comando = comandoEnElOutbox();
        assertThat(comando.ordenId()).isEqualTo(orden.getId());
        assertThat(comando.accion()).isEqualTo(AccionComando.NUEVA);
        assertThat(comando.usuarioId()).isEqualTo(REGISTRADO.toString());
        assertThat(comando.simbolo()).isEqualTo("ANDX");
        assertThat(comando.precioLimite()).isEqualTo(5_000L);
    }

    @Test
    void ordenAMercadoSinPrecioSeGuardaSinPrecio() {
        Orden orden = servicio.crear(REGISTRADO.toString(), orden("CAFE", Lado.VENTA, TipoOrden.MERCADO, null, 10));

        assertThat(orden.getPrecioLimite()).isNull();
        assertThat(comandoEnElOutbox().precioLimite()).isNull();
    }

    @Test
    void ordenDeUnUsuarioDesconocidoSeRechazaYNoGuardaNada() {
        String desconocido = UUID.randomUUID().toString();

        assertThatThrownBy(() -> servicio.crear(desconocido, orden("ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100)))
                .isInstanceOf(UsuarioNoRegistradoException.class)
                .hasMessageContaining(desconocido);
        nadaGuardado();
    }

    @Test
    void unUsuarioIdQueNoEsUnUuidTampocoEsUnUsuarioRegistrado() {
        assertThatThrownBy(() -> servicio.crear("cris", orden("ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100)))
                .isInstanceOf(UsuarioNoRegistradoException.class)
                .hasMessageContaining("cris");
        nadaGuardado();
    }

    @Test
    void ordenLimiteSinPrecioSeRechazaYNoGuardaNada() {
        assertThatThrownBy(() -> servicio.crear(REGISTRADO.toString(), orden("ANDX", Lado.COMPRA, TipoOrden.LIMITE, null, 100)))
                .isInstanceOf(OrdenInvalidaException.class)
                .hasMessageContaining("precioLimite");
        nadaGuardado();
    }

    @Test
    void ordenAMercadoConPrecioSeRechazaYNoGuardaNada() {
        assertThatThrownBy(() -> servicio.crear(REGISTRADO.toString(), orden("ANDX", Lado.VENTA, TipoOrden.MERCADO, 5_000L, 10)))
                .isInstanceOf(OrdenInvalidaException.class)
                .hasMessageContaining("MERCADO");
        nadaGuardado();
    }

    @Test
    void ordenDeUnInstrumentoQueNoExisteSeRechazaYNoGuardaNada() {
        assertThatThrownBy(() -> servicio.crear(REGISTRADO.toString(), orden("NADA", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100)))
                .isInstanceOf(InstrumentoNoDisponibleException.class)
                .hasMessage("El instrumento NADA no existe.");
        nadaGuardado();
    }

    @Test
    void ordenDeUnInstrumentoSuspendidoSeRechazaYNoGuardaNada() {
        when(instrumentos.findById("ANDX")).thenReturn(Optional.of(instrumento("ANDX", EstadoInstrumento.SUSPENDIDO)));

        assertThatThrownBy(() -> servicio.crear(REGISTRADO.toString(), orden("andx", Lado.VENTA, TipoOrden.LIMITE, 5_000L, 100)))
                .isInstanceOf(InstrumentoNoDisponibleException.class)
                .hasMessage("El instrumento ANDX está suspendido: no recibe órdenes nuevas.");
        nadaGuardado();
    }

    @Test
    void buscarLaOrdenDeOtroResponde404ComoUnaQueNoExiste() {
        UUID deOtro = UUID.randomUUID();

        assertThatThrownBy(() -> servicio.buscar(REGISTRADO.toString(), deOtro))
                .isInstanceOf(OrdenNoEncontradaException.class)
                .hasMessage("No hay ninguna orden tuya con el id " + deOtro + ".");
        // Se busca por id y dueño a la vez: no hay forma de distinguir "de otro" de "no existe"
        verify(ordenes).findByIdAndUsuarioId(deOtro, REGISTRADO);
    }

    @Test
    void siHayMasOrdenesQueElTamanoLaPaginaTraeElCursorDeLaUltima() {
        List<Orden> tres = ordenesGuardadas(3);
        when(consulta.buscar(REGISTRADO, FiltroEstado.TODAS, null, 3)).thenReturn(tres);

        PaginaOrdenes pagina = servicio.listar(REGISTRADO.toString(), null, 2, null);

        // Se piden 3 para saber si hay otra página, pero se devuelven 2
        assertThat(pagina.ordenes()).containsExactly(tres.get(0), tres.get(1));
        assertThat(pagina.siguiente()).isEqualTo(Cursor.despuesDe(tres.get(1)));
    }

    @Test
    void laUltimaPaginaNoTraeCursor() {
        List<Orden> dos = ordenesGuardadas(2);
        Cursor anterior = new Cursor(Instant.now(), UUID.randomUUID());
        when(consulta.buscar(REGISTRADO, FiltroEstado.ABIERTAS, anterior, 21)).thenReturn(dos);

        PaginaOrdenes pagina = servicio.listar(REGISTRADO.toString(), "abiertas", 20, anterior.codificar());

        assertThat(pagina.ordenes()).hasSize(2);
        assertThat(pagina.siguiente()).isNull();
    }

    @Test
    void unEstadoPasaComoFiltroDeUnSoloEstado() {
        servicio.listar(REGISTRADO.toString(), "EJECUTADA", 20, null);

        verify(consulta).buscar(REGISTRADO, new FiltroEstado(EstadoOrden.EJECUTADA, false), null, 21);
    }

    @Test
    void unTamanoFueraDeRangoResponde400SinConsultar() {
        assertThatThrownBy(() -> servicio.listar(REGISTRADO.toString(), null, 101, null))
                .isInstanceOf(ConsultaInvalidaException.class)
                .hasMessage("tamano debe estar entre 1 y 100.");
        assertThatThrownBy(() -> servicio.listar(REGISTRADO.toString(), null, 0, null))
                .isInstanceOf(ConsultaInvalidaException.class);
        verifyNoInteractions(consulta);
    }

    @Test
    void unEstadoQueNoExisteResponde400ConLosValoresValidos() {
        assertThatThrownBy(() -> servicio.listar(REGISTRADO.toString(), "VIVAS", 20, null))
                .isInstanceOf(ConsultaInvalidaException.class)
                .hasMessage("estado debe ser uno de: ABIERTAS, PENDIENTE, ABIERTA, PARCIAL, EJECUTADA, CANCELADA, RECHAZADA.");
        verifyNoInteractions(consulta);
    }

    @Test
    void unCursorAlteradoResponde400SinConsultar() {
        assertThatThrownBy(() -> servicio.listar(REGISTRADO.toString(), null, 20, "no-es-un-cursor"))
                .isInstanceOf(ConsultaInvalidaException.class)
                .hasMessageStartingWith("despues no es válido");
        verifyNoInteractions(consulta);
    }

    /** El comando que quedó en el outbox para orders.commands, con el símbolo como clave del mensaje. */
    private ComandoOrden comandoEnElOutbox() {
        ArgumentCaptor<String> clave = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> evento = ArgumentCaptor.forClass(Object.class);
        verify(outbox).guardar(eq(Topicos.ORDERS_COMMANDS), clave.capture(), evento.capture());
        ComandoOrden comando = (ComandoOrden) evento.getValue();
        assertThat(clave.getValue()).isEqualTo(comando.simbolo());
        return comando;
    }

    private void nadaGuardado() {
        verifyNoInteractions(ordenes, outbox);
    }

    private static List<Orden> ordenesGuardadas(int cuantas) {
        Instant ahora = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return IntStream.range(0, cuantas)
                .mapToObj(i -> Orden.nueva(ComandoOrden.nueva(REGISTRADO.toString(), "ANDX", Lado.COMPRA,
                        TipoOrden.LIMITE, 5_000L, 10), REGISTRADO, ahora.minusSeconds(i)))
                .toList();
    }

    private static CrearOrdenRequest orden(String simbolo, Lado lado, TipoOrden tipo, Long precio, int cantidad) {
        return new CrearOrdenRequest(simbolo, lado, tipo, precio, cantidad);
    }

    private static Instrumento instrumento(String simbolo, EstadoInstrumento estado) {
        return new Instrumento(new InstrumentoActualizado(simbolo, "Empresa", "Sector", 5_000, 10, 1_000_000, estado,
                Instant.now()));
    }
}
