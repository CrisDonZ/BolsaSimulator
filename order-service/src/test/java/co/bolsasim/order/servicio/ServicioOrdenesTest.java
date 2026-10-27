package co.bolsasim.order.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.bolsasim.eventos.AccionComando;
import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.EstadoInstrumento;
import co.bolsasim.eventos.InstrumentoActualizado;
import co.bolsasim.eventos.Lado;
import co.bolsasim.eventos.SolicitudReserva;
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
import java.math.BigDecimal;
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
import org.springframework.test.util.ReflectionTestUtils;
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
    void unaOrdenValidaSeGuardaPendienteYPideSuReservaEnElOutbox() {
        Orden orden = servicio.crear(REGISTRADO.toString(), CLAVE, orden("andx", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100)).orden();

        assertThat(orden.getEstado()).isEqualTo(EstadoOrden.PENDIENTE);
        assertThat(orden.getUsuarioId()).isEqualTo(REGISTRADO);
        assertThat(orden.getSimbolo()).isEqualTo("ANDX");
        assertThat(orden.getCantidadEjecutada()).isZero();
        assertThat(orden.getPrecioPromedio()).isNull();

        // Todavía no va al motor: primero portfolio-service reserva el dinero. La solicitud lleva la orden completa
        // y el último precio (el inicial, porque ANDX no ha operado)
        SolicitudReserva solicitud = solicitudEnElOutbox();
        assertThat(solicitud.ordenId()).isEqualTo(orden.getId());
        assertThat(solicitud.usuarioId()).isEqualTo(REGISTRADO.toString());
        assertThat(solicitud.simbolo()).isEqualTo("ANDX");
        assertThat(solicitud.lado()).isEqualTo(Lado.COMPRA);
        assertThat(solicitud.precioLimite()).isEqualTo(5_000L);
        assertThat(solicitud.cantidad()).isEqualTo(100);
        assertThat(solicitud.ultimoPrecio()).isEqualTo(5_000L);
        verify(outbox, never()).guardar(eq(Topicos.ORDERS_COMMANDS), any(), any());
    }

    @Test
    void ordenAMercadoSinPrecioSeGuardaSinPrecioYPideLaReservaConElUltimoPrecio() {
        Orden orden = servicio.crear(REGISTRADO.toString(), CLAVE, orden("CAFE", Lado.VENTA, TipoOrden.MERCADO, null, 10)).orden();

        assertThat(orden.getPrecioLimite()).isNull();
        SolicitudReserva solicitud = solicitudEnElOutbox();
        assertThat(solicitud.precioLimite()).isNull();
        assertThat(solicitud.ultimoPrecio()).isEqualTo(5_000L);
    }

    @Test
    void laOrdenGuardaLaMismaReferenciaConLaQueSeReservaYLaLlevaEnSuComandoNueva() {
        Orden orden = servicio.crear(REGISTRADO.toString(), CLAVE, orden("ANDX", Lado.COMPRA, TipoOrden.MERCADO, null, 10)).orden();

        // La referencia sale del instrumento, no de la solicitud del cliente (RF-26)
        assertThat(orden.getPrecioReferencia()).isEqualTo(solicitudEnElOutbox().ultimoPrecio()).isEqualTo(5_000L);
        ComandoOrden nueva = orden.comandoParaElMotor();
        assertThat(nueva.precioReferencia()).isEqualTo(5_000L);
        assertThat(nueva.precioLimite()).isNull();
    }

    @Test
    void unPrecioQueNoEsMultiploDelTickSeRechazaYNoGuardaNada() {
        assertThatThrownBy(() -> servicio.crear(REGISTRADO.toString(), CLAVE, orden("ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_005L, 100)))
                .isInstanceOf(PrecioInvalidoException.class)
                .hasMessage("El precio 5.005 no es múltiplo del tick de ANDX (10 COP): usa, por ejemplo, 5.000 o 5.010.");
        nadaGuardado();
    }

    @Test
    void unPrecioAMasDeDiezPorCientoDelUltimoSeRechazaYNoGuardaNada() {
        String mensaje = "está a más de 10 % del último precio de ANDX (5.000 COP): debe estar entre 4.500 y 5.500.";
        assertThatThrownBy(() -> servicio.crear(REGISTRADO.toString(), CLAVE, orden("ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_510L, 100)))
                .isInstanceOf(PrecioInvalidoException.class)
                .hasMessage("El precio 5.510 " + mensaje);
        // También hacia abajo, y también en una venta
        assertThatThrownBy(() -> servicio.crear(REGISTRADO.toString(), CLAVE, orden("ANDX", Lado.VENTA, TipoOrden.LIMITE, 4_490L, 100)))
                .isInstanceOf(PrecioInvalidoException.class)
                .hasMessage("El precio 4.490 " + mensaje);
        nadaGuardado();
    }

    @Test
    void losLimitesDeLaBandaSeAceptan() {
        servicio.crear(REGISTRADO.toString(), CLAVE, orden("ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_500L, 1));
        servicio.crear(REGISTRADO.toString(), "otra-" + CLAVE, orden("ANDX", Lado.COMPRA, TipoOrden.LIMITE, 4_500L, 1));

        verify(outbox, times(2)).guardar(eq(Topicos.ORDERS_REQUESTED), eq(REGISTRADO.toString()), any());
    }

    @Test
    void conUnPrecioDeReferenciaQueNoEsRedondoLaBandaSeRedondeaHaciaAdentro() {
        // 10 % de 3.505 es 350,5: el mínimo queda en 3.155 (3.154,5 hacia arriba) y el máximo en 3.855 (3.855,5
        // hacia abajo). Con tick 5, 3.150 y 3.860 son los primeros precios por fuera
        when(instrumentos.findById("TAYR")).thenReturn(Optional.of(instrumento("TAYR", EstadoInstrumento.ACTIVO, 3_505, 5)));

        servicio.crear(REGISTRADO.toString(), CLAVE, orden("TAYR", Lado.COMPRA, TipoOrden.LIMITE, 3_155L, 1));
        servicio.crear(REGISTRADO.toString(), "otra-" + CLAVE, orden("TAYR", Lado.COMPRA, TipoOrden.LIMITE, 3_855L, 1));
        assertThatThrownBy(() -> servicio.crear(REGISTRADO.toString(), "tercera-" + CLAVE,
                orden("TAYR", Lado.COMPRA, TipoOrden.LIMITE, 3_150L, 1)))
                .isInstanceOf(PrecioInvalidoException.class)
                .hasMessageEndingWith("debe estar entre 3.155 y 3.855.");
        assertThatThrownBy(() -> servicio.crear(REGISTRADO.toString(), "cuarta-" + CLAVE,
                orden("TAYR", Lado.COMPRA, TipoOrden.LIMITE, 3_860L, 1)))
                .isInstanceOf(PrecioInvalidoException.class);
    }

    @Test
    void unPrecioEnormeSeRechazaSinDesbordarse() {
        // Múltiplo del tick, para que llegue a la banda: 10 × precio no cabría en un long
        assertThatThrownBy(() -> servicio.crear(REGISTRADO.toString(), CLAVE,
                orden("ANDX", Lado.COMPRA, TipoOrden.LIMITE, 922_337_203_685_477_580L, 1)))
                .isInstanceOf(PrecioInvalidoException.class)
                .hasMessageEndingWith("debe estar entre 4.500 y 5.500.");
        nadaGuardado();
    }

    @Test
    void unInstrumentoSinPrecioDeReferenciaSeRechazaYNoGuardaNada() {
        Instrumento sinPrecio = mock(Instrumento.class);
        when(sinPrecio.activo()).thenReturn(true);
        when(sinPrecio.getSimbolo()).thenReturn("ANDX");
        when(sinPrecio.precioDeReferencia()).thenReturn(Optional.empty());
        when(instrumentos.findById("ANDX")).thenReturn(Optional.of(sinPrecio));

        assertThatThrownBy(() -> servicio.crear(REGISTRADO.toString(), CLAVE, orden("ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100)))
                .isInstanceOf(InstrumentoNoDisponibleException.class)
                .hasMessageStartingWith("El instrumento ANDX todavía no tiene precio de referencia");
        nadaGuardado();
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
    void unaOrdenAbiertaSeCancelaConUnComandoCancelarEnElOutboxYSuEstadoNoCambiaTodavia() {
        Orden abierta = enEstado(EstadoOrden.ABIERTA, 0);
        when(ordenes.solicitarCancelacion(eq(abierta.getId()), eq(REGISTRADO), any())).thenReturn(1);
        when(ordenes.findByIdAndUsuarioId(abierta.getId(), REGISTRADO)).thenReturn(Optional.of(abierta));

        Orden resultado = servicio.cancelar(REGISTRADO.toString(), abierta.getId());

        // Queda ABIERTA hasta que el motor confirme con CANCELADA
        assertThat(resultado.getEstado()).isEqualTo(EstadoOrden.ABIERTA);
        ComandoOrden comando = cancelarEnElOutbox();
        assertThat(comando.accion()).isEqualTo(AccionComando.CANCELAR);
        assertThat(comando.ordenId()).isEqualTo(abierta.getId());
        assertThat(comando.usuarioId()).isEqualTo(REGISTRADO.toString());
        assertThat(comando.simbolo()).isEqualTo("ANDX");
    }

    @Test
    void unaOrdenParcialSeCancelaIgualYConservaLoEjecutado() {
        Orden parcial = enEstado(EstadoOrden.PARCIAL, 40);
        when(ordenes.solicitarCancelacion(eq(parcial.getId()), eq(REGISTRADO), any())).thenReturn(1);
        when(ordenes.findByIdAndUsuarioId(parcial.getId(), REGISTRADO)).thenReturn(Optional.of(parcial));

        Orden resultado = servicio.cancelar(REGISTRADO.toString(), parcial.getId());

        assertThat(resultado.getCantidadEjecutada()).isEqualTo(40);
        assertThat(cancelarEnElOutbox().ordenId()).isEqualTo(parcial.getId());
    }

    @Test
    void unaOrdenPendienteNoSeCancelaPorqueTodaviaNoLlegaAlLibro() {
        Orden pendiente = enEstado(EstadoOrden.PENDIENTE, 0);
        when(ordenes.findByIdAndUsuarioId(pendiente.getId(), REGISTRADO)).thenReturn(Optional.of(pendiente));

        assertThatThrownBy(() -> servicio.cancelar(REGISTRADO.toString(), pendiente.getId()))
                .isInstanceOf(OrdenNoCancelableException.class)
                .hasMessage("La orden todavía se está procesando; intenta cancelarla en unos segundos.");
        verifyNoInteractions(outbox);
    }

    @Test
    void unaOrdenTerminadaNoSeCancela() {
        for (EstadoOrden terminado : List.of(EstadoOrden.EJECUTADA, EstadoOrden.CANCELADA, EstadoOrden.RECHAZADA)) {
            Orden orden = enEstado(terminado, terminado == EstadoOrden.EJECUTADA ? 100 : 0);
            when(ordenes.findByIdAndUsuarioId(orden.getId(), REGISTRADO)).thenReturn(Optional.of(orden));

            assertThatThrownBy(() -> servicio.cancelar(REGISTRADO.toString(), orden.getId()))
                    .as("orden %s", terminado)
                    .isInstanceOf(OrdenNoCancelableException.class)
                    .hasMessage("La orden ya terminó (" + terminado + "): no se puede cancelar.");
        }
        verifyNoInteractions(outbox);
    }

    @Test
    void cancelarLaOrdenDeOtroResponde404ComoUnaQueNoExiste() {
        UUID deOtro = UUID.randomUUID();

        assertThatThrownBy(() -> servicio.cancelar(REGISTRADO.toString(), deOtro))
                .isInstanceOf(OrdenNoEncontradaException.class)
                .hasMessage("No hay ninguna orden tuya con el id " + deOtro + ".");
        // El UPDATE y la búsqueda filtran por id y dueño a la vez: la de otro no se toca ni se distingue
        verify(ordenes).solicitarCancelacion(eq(deOtro), eq(REGISTRADO), any());
        verify(ordenes).findByIdAndUsuarioId(deOtro, REGISTRADO);
        verifyNoInteractions(outbox);
    }

    @Test
    void unaCancelacionRepetidaDevuelveLaOrdenSinPublicarOtroComando() {
        Orden yaPedida = enEstado(EstadoOrden.ABIERTA, 0);
        ReflectionTestUtils.setField(yaPedida, "cancelacionSolicitadaEn", Instant.now());
        // El UPDATE no la marca otra vez: ya tiene cancelacion_solicitada_en
        when(ordenes.findByIdAndUsuarioId(yaPedida.getId(), REGISTRADO)).thenReturn(Optional.of(yaPedida));

        Orden resultado = servicio.cancelar(REGISTRADO.toString(), yaPedida.getId());

        assertThat(resultado).isSameAs(yaPedida);
        verifyNoInteractions(outbox);
    }

    @Test
    void unInstrumentoSuspendidoNoImpideCancelar() {
        when(instrumentos.findById("ANDX")).thenReturn(Optional.of(instrumento("ANDX", EstadoInstrumento.SUSPENDIDO)));
        Orden abierta = enEstado(EstadoOrden.ABIERTA, 0);
        when(ordenes.solicitarCancelacion(eq(abierta.getId()), eq(REGISTRADO), any())).thenReturn(1);
        when(ordenes.findByIdAndUsuarioId(abierta.getId(), REGISTRADO)).thenReturn(Optional.of(abierta));

        servicio.cancelar(REGISTRADO.toString(), abierta.getId());

        // RF-29: el instrumento ni se consulta
        verify(instrumentos, never()).findById(any());
        assertThat(cancelarEnElOutbox().ordenId()).isEqualTo(abierta.getId());
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

    /** La solicitud que quedó en el outbox para orders.requested, con el usuario como clave del mensaje. */
    private SolicitudReserva solicitudEnElOutbox() {
        ArgumentCaptor<String> clave = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> evento = ArgumentCaptor.forClass(Object.class);
        verify(outbox).guardar(eq(Topicos.ORDERS_REQUESTED), clave.capture(), evento.capture());
        SolicitudReserva solicitud = (SolicitudReserva) evento.getValue();
        assertThat(clave.getValue()).isEqualTo(solicitud.usuarioId());
        return solicitud;
    }

    /** El comando CANCELAR que quedó en el outbox para orders.commands, con el símbolo como clave, como su NUEVA. */
    private ComandoOrden cancelarEnElOutbox() {
        ArgumentCaptor<Object> evento = ArgumentCaptor.forClass(Object.class);
        verify(outbox).guardar(eq(Topicos.ORDERS_COMMANDS), eq("ANDX"), evento.capture());
        return (ComandoOrden) evento.getValue();
    }

    /** Una compra de 100 ANDX a 5.000 que el motor (o la reserva) llevó a ese estado. */
    private static Orden enEstado(EstadoOrden estado, int ejecutada) {
        Orden orden = ordenCon("clave-" + UUID.randomUUID(), "ANDX", 100);
        Instant ahora = Instant.now();
        switch (estado) {
            case PENDIENTE -> {
            }
            case RECHAZADA -> orden.rechazar("Saldo insuficiente", ahora);
            case CANCELADA -> orden.aplicar(EstadoOrden.CANCELADA, 100, ejecutada, null, "Cancelada", ahora);
            default -> orden.aplicar(estado, 100, ejecutada, ejecutada == 0 ? null : BigDecimal.valueOf(5_000), null,
                    ahora);
        }
        return orden;
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

    /** Precio inicial 5.000 y tick 10, como ANDX. */
    private static Instrumento instrumento(String simbolo, EstadoInstrumento estado) {
        return instrumento(simbolo, estado, 5_000, 10);
    }

    private static Instrumento instrumento(String simbolo, EstadoInstrumento estado, long precioInicial, long tick) {
        return new Instrumento(new InstrumentoActualizado(simbolo, "Empresa", "Sector", precioInicial, tick, 1_000_000,
                estado, Instant.now()));
    }
}
