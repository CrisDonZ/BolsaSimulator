package co.bolsasim.order.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import co.bolsasim.eventos.EstadoOrden;
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
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Prueba unitaria: no levanta Spring, Kafka ni base de datos. Los repositorios, la consulta y el outbox son
 * "dobles" de Mockito, así comprobamos solo las reglas del servicio.
 */
class ServicioOrdenesTest {

    private static final UUID REGISTRADO = UUID.randomUUID();
    private static final String CLAVE = "8e03978e-40d5-43e8-bc93-6894a57f9324";

    private final RepositorioOrdenes ordenes = mock(RepositorioOrdenes.class);
    private final ConsultaOrdenes consulta = mock(ConsultaOrdenes.class);
    private final Outbox outbox = mock(Outbox.class);
    private final RepositorioUsuarios usuarios = mock(RepositorioUsuarios.class);
    private final RepositorioInstrumentos instrumentos = mock(RepositorioInstrumentos.class);
    private final ServicioOrdenes servicio = new ServicioOrdenes(ordenes, consulta, outbox, usuarios, instrumentos,
            mock(PlatformTransactionManager.class));

    @BeforeEach
    void soloExistenUnUsuarioRegistradoYDosInstrumentosActivos() {
        when(usuarios.existsById(REGISTRADO)).thenReturn(true);
        when(instrumentos.findById("ANDX")).thenReturn(Optional.of(instrumento("ANDX", EstadoInstrumento.ACTIVO)));
        when(instrumentos.findById("CAFE")).thenReturn(Optional.of(instrumento("CAFE", EstadoInstrumento.ACTIVO)));
        when(ordenes.saveAndFlush(any())).thenAnswer(invocacion -> invocacion.getArgument(0));
    }

    @Test
    void unaOrdenValidaSeGuardaPendienteYSuComandoQuedaEnElOutbox() {
        Orden orden = servicio.crear(REGISTRADO.toString(), CLAVE, orden("andx", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100)).orden();

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
        Orden orden = servicio.crear(REGISTRADO.toString(), CLAVE, orden("CAFE", Lado.VENTA, TipoOrden.MERCADO, null, 10)).orden();

        assertThat(orden.getPrecioLimite()).isNull();
        assertThat(comandoEnElOutbox().precioLimite()).isNull();
    }

    @Test
    void ordenDeUnUsuarioDesconocidoSeRechazaYNoGuardaNada() {
        String desconocido = UUID.randomUUID().toString();

        assertThatThrownBy(() -> servicio.crear(desconocido, CLAVE, orden("ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100)))
                .isInstanceOf(UsuarioNoRegistradoException.class)
                .hasMessageContaining(desconocido);
        nadaGuardado();
    }

    @Test
    void unUsuarioIdQueNoEsUnUuidTampocoEsUnUsuarioRegistrado() {
        assertThatThrownBy(() -> servicio.crear("cris", CLAVE, orden("ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100)))
                .isInstanceOf(UsuarioNoRegistradoException.class)
                .hasMessageContaining("cris");
        nadaGuardado();
    }

    @Test
    void ordenLimiteSinPrecioSeRechazaYNoGuardaNada() {
        assertThatThrownBy(() -> servicio.crear(REGISTRADO.toString(), CLAVE, orden("ANDX", Lado.COMPRA, TipoOrden.LIMITE, null, 100)))
                .isInstanceOf(OrdenInvalidaException.class)
                .hasMessageContaining("precioLimite");
        nadaGuardado();
    }

    @Test
    void ordenAMercadoConPrecioSeRechazaYNoGuardaNada() {
        assertThatThrownBy(() -> servicio.crear(REGISTRADO.toString(), CLAVE, orden("ANDX", Lado.VENTA, TipoOrden.MERCADO, 5_000L, 10)))
                .isInstanceOf(OrdenInvalidaException.class)
                .hasMessageContaining("MERCADO");
        nadaGuardado();
    }

    @Test
    void ordenDeUnInstrumentoQueNoExisteSeRechazaYNoGuardaNada() {
        assertThatThrownBy(() -> servicio.crear(REGISTRADO.toString(), CLAVE, orden("NADA", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100)))
                .isInstanceOf(InstrumentoNoDisponibleException.class)
                .hasMessage("El instrumento NADA no existe.");
        nadaGuardado();
    }

    @Test
    void ordenDeUnInstrumentoSuspendidoSeRechazaYNoGuardaNada() {
        when(instrumentos.findById("ANDX")).thenReturn(Optional.of(instrumento("ANDX", EstadoInstrumento.SUSPENDIDO)));

        assertThatThrownBy(() -> servicio.crear(REGISTRADO.toString(), CLAVE, orden("andx", Lado.VENTA, TipoOrden.LIMITE, 5_000L, 100)))
                .isInstanceOf(InstrumentoNoDisponibleException.class)
                .hasMessage("El instrumento ANDX está suspendido: no recibe órdenes nuevas.");
        nadaGuardado();
    }

    @Test
    void laOrdenGuardaLaClaveDeIdempotenciaYLaBuscaSoloEntreLasDelUsuario() {
        Orden orden = servicio.crear(REGISTRADO.toString(), CLAVE, orden("ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100)).orden();

        assertThat(orden.getClaveIdempotencia()).isEqualTo(CLAVE);
        verify(ordenes).findByUsuarioIdAndClaveIdempotencia(REGISTRADO, CLAVE);
    }

    @Test
    void laMismaSolicitudConLaMismaClaveDevuelveLaOrdenOriginalSinGuardarNada() {
        Orden original = ordenCon(CLAVE, "ANDX", 100);
        when(ordenes.findByUsuarioIdAndClaveIdempotencia(REGISTRADO, CLAVE)).thenReturn(Optional.of(original));

        // "andx" en minúsculas es la misma orden
        OrdenCreada repetida = servicio.crear(REGISTRADO.toString(), CLAVE, orden("andx", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100));

        assertThat(repetida.repetida()).isTrue();
        assertThat(repetida.orden()).isSameAs(original);
        nadaGuardado();
    }

    @Test
    void elReintentoRecibeSuOrdenAunqueElInstrumentoSeHayaSuspendidoDespues() {
        Orden original = ordenCon(CLAVE, "ANDX", 100);
        when(ordenes.findByUsuarioIdAndClaveIdempotencia(REGISTRADO, CLAVE)).thenReturn(Optional.of(original));
        when(instrumentos.findById("ANDX")).thenReturn(Optional.of(instrumento("ANDX", EstadoInstrumento.SUSPENDIDO)));

        OrdenCreada repetida = servicio.crear(REGISTRADO.toString(), CLAVE, orden("ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100));

        assertThat(repetida.orden()).isSameAs(original);
    }

    @Test
    void laMismaClaveConOtraOrdenSeRechazaYNoGuardaNada() {
        when(ordenes.findByUsuarioIdAndClaveIdempotencia(REGISTRADO, CLAVE)).thenReturn(Optional.of(ordenCon(CLAVE, "ANDX", 100)));

        assertThatThrownBy(() -> servicio.crear(REGISTRADO.toString(), CLAVE, orden("ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 200)))
                .isInstanceOf(ClaveIdempotenciaReusadaException.class)
                .hasMessage("Esa Idempotency-Key ya se usó para otra orden: usa una clave nueva para cada orden.");
        nadaGuardado();
    }

    @Test
    void siOtraSolicitudConLaMismaClaveGanoLaCarreraSeDevuelveSuOrden() {
        // La búsqueda no la ve (la otra no había confirmado), pero el índice único rechaza el INSERT
        Orden laOtra = ordenCon(CLAVE, "ANDX", 100);
        when(ordenes.findByUsuarioIdAndClaveIdempotencia(REGISTRADO, CLAVE))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(laOtra));
        when(ordenes.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("clave repetida",
                new ConstraintViolationException("duplicate key", null, ServicioOrdenes.INDICE_CLAVE)));

        OrdenCreada resultado = servicio.crear(REGISTRADO.toString(), CLAVE, orden("ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100));

        assertThat(resultado.repetida()).isTrue();
        assertThat(resultado.orden()).isSameAs(laOtra);
        verifyNoInteractions(outbox);
    }

    @Test
    void otraViolacionDeLaBaseNoSeConfundeConUnaClaveRepetida() {
        DataIntegrityViolationException otra = new DataIntegrityViolationException("otra regla",
                new ConstraintViolationException("check", null, "orden_cantidad_check"));
        when(ordenes.saveAndFlush(any())).thenThrow(otra);

        assertThatThrownBy(() -> servicio.crear(REGISTRADO.toString(), CLAVE, orden("ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100)))
                .isSameAs(otra);
    }

    @Test
    void sinIdempotencyKeySeRechazaSinBuscarNiGuardar() {
        assertThatThrownBy(() -> servicio.crear(REGISTRADO.toString(), null, orden("ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100)))
                .isInstanceOf(ClaveIdempotenciaInvalidaException.class)
                .hasMessageStartingWith("Falta el header Idempotency-Key");
        verifyNoInteractions(ordenes, outbox);
    }

    @Test
    void unaClaveConOtroFormatoSeRechaza() {
        for (String invalida : new String[] {"1", "abc", "clave con espacios!!", "x".repeat(65), "  "}) {
            assertThatThrownBy(() -> servicio.crear(REGISTRADO.toString(), invalida, orden("ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100)))
                    .as("clave %s", invalida)
                    .isInstanceOf(ClaveIdempotenciaInvalidaException.class);
        }
        verifyNoInteractions(ordenes, outbox);
    }

    @Test
    void laClaveEntreComillasEsLaMismaClave() {
        Orden orden = servicio.crear(REGISTRADO.toString(), "\"" + CLAVE + "\"", orden("ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100)).orden();

        assertThat(orden.getClaveIdempotencia()).isEqualTo(CLAVE);
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
        verify(ordenes, never()).saveAndFlush(any());
        verifyNoInteractions(outbox);
    }

    private static List<Orden> ordenesGuardadas(int cuantas) {
        Instant ahora = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return IntStream.range(0, cuantas)
                .mapToObj(i -> Orden.nueva(ComandoOrden.nueva(REGISTRADO.toString(), "ANDX", Lado.COMPRA,
                        TipoOrden.LIMITE, 5_000L, 10), REGISTRADO, "clave-" + UUID.randomUUID(), ahora.minusSeconds(i)))
                .toList();
    }

    /** Una compra LIMITE a 5.000 que ya existe con esa clave. */
    private static Orden ordenCon(String clave, String simbolo, int cantidad) {
        return Orden.nueva(ComandoOrden.nueva(REGISTRADO.toString(), simbolo, Lado.COMPRA, TipoOrden.LIMITE, 5_000L, cantidad),
                REGISTRADO, clave, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    private static CrearOrdenRequest orden(String simbolo, Lado lado, TipoOrden tipo, Long precio, int cantidad) {
        return new CrearOrdenRequest(simbolo, lado, tipo, precio, cantidad);
    }

    private static Instrumento instrumento(String simbolo, EstadoInstrumento estado) {
        return new Instrumento(new InstrumentoActualizado(simbolo, "Empresa", "Sector", 5_000, 10, 1_000_000, estado,
                Instant.now()));
    }
}
