package co.bolsasim.order.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import co.bolsasim.eventos.AccionComando;
import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.Lado;
import co.bolsasim.eventos.TipoOrden;
import co.bolsasim.order.web.CrearOrdenRequest;
import org.junit.jupiter.api.Test;

/**
 * Prueba unitaria: no levanta Spring ni Kafka. El publicador es un "doble"
 * de Mockito, así comprobamos solo las reglas del servicio.
 */
class ServicioOrdenesTest {

    private final PublicadorOrdenes publicador = mock(PublicadorOrdenes.class);
    private final ServicioOrdenes servicio = new ServicioOrdenes(publicador);

    @Test
    void ordenLimiteValidaSePublicaConElSimboloEnMayusculas() {
        var request = new CrearOrdenRequest("cris", "andx", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100);

        ComandoOrden comando = servicio.crear(request);

        assertThat(comando.simbolo()).isEqualTo("ANDX");
        assertThat(comando.accion()).isEqualTo(AccionComando.NUEVA);
        assertThat(comando.precioLimite()).isEqualTo(5_000L);
        assertThat(comando.ordenId()).isNotNull();
        verify(publicador).publicar(comando);
    }

    @Test
    void ordenLimiteSinPrecioSeRechazaYNoSePublica() {
        var request = new CrearOrdenRequest("cris", "ANDX", Lado.COMPRA, TipoOrden.LIMITE, null, 100);

        assertThatThrownBy(() -> servicio.crear(request))
                .isInstanceOf(OrdenInvalidaException.class)
                .hasMessageContaining("precioLimite");
        verifyNoInteractions(publicador);
    }

    @Test
    void ordenAMercadoConPrecioSeRechazaYNoSePublica() {
        var request = new CrearOrdenRequest("cris", "ANDX", Lado.VENTA, TipoOrden.MERCADO, 5_000L, 10);

        assertThatThrownBy(() -> servicio.crear(request))
                .isInstanceOf(OrdenInvalidaException.class)
                .hasMessageContaining("MERCADO");
        verifyNoInteractions(publicador);
    }

    @Test
    void ordenAMercadoSinPrecioSePublica() {
        var request = new CrearOrdenRequest("cris", "CAFE", Lado.VENTA, TipoOrden.MERCADO, null, 10);

        ComandoOrden comando = servicio.crear(request);

        assertThat(comando.precioLimite()).isNull();
        verify(publicador).publicar(comando);
    }
}
