package co.bolsasim.order.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import co.bolsasim.order.instrumento.Instrumento;
import co.bolsasim.order.instrumento.RepositorioInstrumentos;
import co.bolsasim.order.usuario.RepositorioUsuarios;
import co.bolsasim.order.web.CrearOrdenRequest;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Prueba unitaria: no levanta Spring, Kafka ni base de datos. El publicador y las copias locales
 * de usuarios e instrumentos son "dobles" de Mockito, así comprobamos solo las reglas del servicio.
 */
class ServicioOrdenesTest {

    private static final UUID REGISTRADO = UUID.randomUUID();

    private final PublicadorOrdenes publicador = mock(PublicadorOrdenes.class);
    private final RepositorioUsuarios usuarios = mock(RepositorioUsuarios.class);
    private final RepositorioInstrumentos instrumentos = mock(RepositorioInstrumentos.class);
    private final ServicioOrdenes servicio = new ServicioOrdenes(publicador, usuarios, instrumentos);

    @BeforeEach
    void soloExistenUnUsuarioRegistradoYDosInstrumentosActivos() {
        when(usuarios.existsById(REGISTRADO)).thenReturn(true);
        when(instrumentos.findById("ANDX")).thenReturn(Optional.of(instrumento("ANDX", EstadoInstrumento.ACTIVO)));
        when(instrumentos.findById("CAFE")).thenReturn(Optional.of(instrumento("CAFE", EstadoInstrumento.ACTIVO)));
    }

    @Test
    void ordenDeUnInstrumentoQueNoExisteSeRechazaYNoSePublica() {
        var request = orden("NADA", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100);

        assertThatThrownBy(() -> servicio.crear(REGISTRADO.toString(), request))
                .isInstanceOf(InstrumentoNoDisponibleException.class)
                .hasMessage("El instrumento NADA no existe.");
        verifyNoInteractions(publicador);
    }

    @Test
    void ordenDeUnInstrumentoSuspendidoSeRechazaYNoSePublica() {
        when(instrumentos.findById("ANDX")).thenReturn(Optional.of(instrumento("ANDX", EstadoInstrumento.SUSPENDIDO)));
        var request = orden("andx", Lado.VENTA, TipoOrden.LIMITE, 5_000L, 100);

        assertThatThrownBy(() -> servicio.crear(REGISTRADO.toString(), request))
                .isInstanceOf(InstrumentoNoDisponibleException.class)
                .hasMessage("El instrumento ANDX está suspendido: no recibe órdenes nuevas.");
        verifyNoInteractions(publicador);
    }

    @Test
    void ordenLimiteValidaSePublicaConElSimboloEnMayusculas() {
        var request = orden("andx", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100);

        ComandoOrden comando = servicio.crear(REGISTRADO.toString(), request);

        assertThat(comando.simbolo()).isEqualTo("ANDX");
        assertThat(comando.accion()).isEqualTo(AccionComando.NUEVA);
        assertThat(comando.precioLimite()).isEqualTo(5_000L);
        assertThat(comando.ordenId()).isNotNull();
        verify(publicador).publicar(comando);
    }

    @Test
    void laOrdenSePublicaANombreDelUsuarioQueRecibe() {
        ComandoOrden comando = servicio.crear(REGISTRADO.toString(), orden("ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100));

        assertThat(comando.usuarioId()).isEqualTo(REGISTRADO.toString());
        verify(publicador).publicar(comando);
    }

    @Test
    void ordenDeUnUsuarioDesconocidoSeRechazaYNoSePublica() {
        String desconocido = UUID.randomUUID().toString();
        var request = orden("ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100);

        assertThatThrownBy(() -> servicio.crear(desconocido, request))
                .isInstanceOf(UsuarioNoRegistradoException.class)
                .hasMessageContaining(desconocido);
        verifyNoInteractions(publicador);
    }

    @Test
    void unUsuarioIdQueNoEsUnUuidTampocoEsUnUsuarioRegistrado() {
        var request = orden("ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100);

        assertThatThrownBy(() -> servicio.crear("cris", request))
                .isInstanceOf(UsuarioNoRegistradoException.class)
                .hasMessageContaining("cris");
        verifyNoInteractions(publicador);
    }

    @Test
    void ordenLimiteSinPrecioSeRechazaYNoSePublica() {
        var request = orden("ANDX", Lado.COMPRA, TipoOrden.LIMITE, null, 100);

        assertThatThrownBy(() -> servicio.crear(REGISTRADO.toString(), request))
                .isInstanceOf(OrdenInvalidaException.class)
                .hasMessageContaining("precioLimite");
        verifyNoInteractions(publicador);
    }

    @Test
    void ordenAMercadoConPrecioSeRechazaYNoSePublica() {
        var request = orden("ANDX", Lado.VENTA, TipoOrden.MERCADO, 5_000L, 10);

        assertThatThrownBy(() -> servicio.crear(REGISTRADO.toString(), request))
                .isInstanceOf(OrdenInvalidaException.class)
                .hasMessageContaining("MERCADO");
        verifyNoInteractions(publicador);
    }

    @Test
    void ordenAMercadoSinPrecioSePublica() {
        var request = orden("CAFE", Lado.VENTA, TipoOrden.MERCADO, null, 10);

        ComandoOrden comando = servicio.crear(REGISTRADO.toString(), request);

        assertThat(comando.precioLimite()).isNull();
        verify(publicador).publicar(comando);
    }

    private static CrearOrdenRequest orden(String simbolo, Lado lado, TipoOrden tipo, Long precio, int cantidad) {
        return new CrearOrdenRequest(simbolo, lado, tipo, precio, cantidad);
    }

    private static Instrumento instrumento(String simbolo, EstadoInstrumento estado) {
        return new Instrumento(new InstrumentoActualizado(simbolo, "Empresa", "Sector", 5_000, 10, 1_000_000, estado,
                Instant.now()));
    }
}
