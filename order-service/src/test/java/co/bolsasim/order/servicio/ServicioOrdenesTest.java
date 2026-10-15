package co.bolsasim.order.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.bolsasim.eventos.AccionComando;
import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.Lado;
import co.bolsasim.eventos.TipoOrden;
import co.bolsasim.order.usuario.RepositorioUsuarios;
import co.bolsasim.order.web.CrearOrdenRequest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Prueba unitaria: no levanta Spring, Kafka ni base de datos. El publicador y el repositorio
 * de usuarios son "dobles" de Mockito, así comprobamos solo las reglas del servicio.
 */
class ServicioOrdenesTest {

    private static final UUID REGISTRADO = UUID.randomUUID();

    private final PublicadorOrdenes publicador = mock(PublicadorOrdenes.class);
    private final RepositorioUsuarios usuarios = mock(RepositorioUsuarios.class);
    private final ServicioOrdenes servicio = new ServicioOrdenes(publicador, usuarios);

    @BeforeEach
    void soloExisteUnUsuarioRegistrado() {
        when(usuarios.existsById(REGISTRADO)).thenReturn(true);
    }

    @Test
    void ordenLimiteValidaSePublicaConElSimboloEnMayusculas() {
        var request = orden("andx", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100);

        ComandoOrden comando = servicio.crear(request);

        assertThat(comando.simbolo()).isEqualTo("ANDX");
        assertThat(comando.accion()).isEqualTo(AccionComando.NUEVA);
        assertThat(comando.precioLimite()).isEqualTo(5_000L);
        assertThat(comando.ordenId()).isNotNull();
        verify(publicador).publicar(comando);
    }

    @Test
    void ordenDeUnUsuarioRegistradoSePublicaConSuId() {
        ComandoOrden comando = servicio.crear(orden("ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100));

        assertThat(comando.usuarioId()).isEqualTo(REGISTRADO.toString());
        verify(publicador).publicar(comando);
    }

    @Test
    void ordenDeUnUsuarioDesconocidoSeRechazaYNoSePublica() {
        String desconocido = UUID.randomUUID().toString();
        var request = new CrearOrdenRequest(desconocido, "ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100);

        assertThatThrownBy(() -> servicio.crear(request))
                .isInstanceOf(UsuarioNoRegistradoException.class)
                .hasMessageContaining(desconocido);
        verifyNoInteractions(publicador);
    }

    @Test
    void unUsuarioIdQueNoEsUnUuidTampocoEsUnUsuarioRegistrado() {
        var request = new CrearOrdenRequest("cris", "ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100);

        assertThatThrownBy(() -> servicio.crear(request))
                .isInstanceOf(UsuarioNoRegistradoException.class)
                .hasMessageContaining("cris");
        verifyNoInteractions(publicador);
    }

    @Test
    void ordenLimiteSinPrecioSeRechazaYNoSePublica() {
        var request = orden("ANDX", Lado.COMPRA, TipoOrden.LIMITE, null, 100);

        assertThatThrownBy(() -> servicio.crear(request))
                .isInstanceOf(OrdenInvalidaException.class)
                .hasMessageContaining("precioLimite");
        verifyNoInteractions(publicador);
    }

    @Test
    void ordenAMercadoConPrecioSeRechazaYNoSePublica() {
        var request = orden("ANDX", Lado.VENTA, TipoOrden.MERCADO, 5_000L, 10);

        assertThatThrownBy(() -> servicio.crear(request))
                .isInstanceOf(OrdenInvalidaException.class)
                .hasMessageContaining("MERCADO");
        verifyNoInteractions(publicador);
    }

    @Test
    void ordenAMercadoSinPrecioSePublica() {
        var request = orden("CAFE", Lado.VENTA, TipoOrden.MERCADO, null, 10);

        ComandoOrden comando = servicio.crear(request);

        assertThat(comando.precioLimite()).isNull();
        verify(publicador).publicar(comando);
    }

    /** Una orden del usuario registrado. */
    private static CrearOrdenRequest orden(String simbolo, Lado lado, TipoOrden tipo, Long precio, int cantidad) {
        return new CrearOrdenRequest(REGISTRADO.toString(), simbolo, lado, tipo, precio, cantidad);
    }
}
