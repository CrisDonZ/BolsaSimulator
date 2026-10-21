package co.bolsasim.order.orden;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.bolsasim.eventos.AccionComando;
import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.EstadoOrden;
import co.bolsasim.eventos.Lado;
import co.bolsasim.eventos.TipoOrden;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Prueba unitaria de cómo cambia una orden con la respuesta de la reserva y los eventos del motor: Java puro. */
class OrdenTest {

    private static final Instant AHORA = Instant.parse("2026-10-20T15:00:00Z");

    private final Orden orden = Orden.nueva(
            ComandoOrden.nueva(UUID.randomUUID().toString(), "ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100),
            UUID.randomUUID(), "clave-" + UUID.randomUUID(), AHORA);

    @Test
    void unEventoValidoCambiaEstadoCantidadPrecioYFecha() {
        orden.aplicar(EstadoOrden.PARCIAL, 100, 40, new BigDecimal("5000.0000"), null, AHORA.plusSeconds(1));

        assertThat(orden.getEstado()).isEqualTo(EstadoOrden.PARCIAL);
        assertThat(orden.getCantidadEjecutada()).isEqualTo(40);
        assertThat(orden.getPrecioPromedio()).isEqualByComparingTo("5000");
        assertThat(orden.getActualizadaEn()).isEqualTo(AHORA.plusSeconds(1));
    }

    @Test
    void unaCanceladaGuardaElMotivoYLoQueSeEjecuto() {
        orden.aplicar(EstadoOrden.CANCELADA, 100, 60, new BigDecimal("5000.0000"), "Se ejecutaron 60 de 100", AHORA);

        assertThat(orden.getEstado()).isEqualTo(EstadoOrden.CANCELADA);
        assertThat(orden.getCantidadEjecutada()).isEqualTo(60);
        assertThat(orden.getMotivo()).isEqualTo("Se ejecutaron 60 de 100");
    }

    @Test
    void nuncaSeEjecutaMasDeLoPedido() {
        assertThatThrownBy(() -> orden.aplicar(EstadoOrden.EJECUTADA, 100, 120, BigDecimal.TEN, null, AHORA))
                .isInstanceOf(EventoImposibleException.class)
                .hasMessage("se ejecutarían 120 de 100 acciones");
        assertThat(orden.getEstado()).isEqualTo(EstadoOrden.PENDIENTE);
        assertThat(orden.getCantidadEjecutada()).isZero();
    }

    @Test
    void unaOrdenTerminadaYaNoCambia() {
        orden.aplicar(EstadoOrden.EJECUTADA, 100, 100, new BigDecimal("5000.0000"), null, AHORA);

        assertThatThrownBy(() -> orden.aplicar(EstadoOrden.PARCIAL, 100, 100, BigDecimal.TEN, null, AHORA))
                .isInstanceOf(EventoImposibleException.class)
                .hasMessage("la orden ya terminó (EJECUTADA)");
    }

    @Test
    void loEjecutadoNoRetrocede() {
        orden.aplicar(EstadoOrden.PARCIAL, 100, 60, BigDecimal.TEN, null, AHORA);

        assertThatThrownBy(() -> orden.aplicar(EstadoOrden.PARCIAL, 100, 40, BigDecimal.TEN, null, AHORA))
                .isInstanceOf(EventoImposibleException.class);
    }

    @Test
    void ejecutadaSinEjecutarTodoEsImposible() {
        assertThatThrownBy(() -> orden.aplicar(EstadoOrden.EJECUTADA, 100, 99, BigDecimal.TEN, null, AHORA))
                .isInstanceOf(EventoImposibleException.class);
    }

    @Test
    void unEventoDeOtraCantidadOConUnEstadoQueElMotorNoUsaEsImposible() {
        assertThatThrownBy(() -> orden.aplicar(EstadoOrden.ABIERTA, 200, 0, null, null, AHORA))
                .isInstanceOf(EventoImposibleException.class);
        assertThatThrownBy(() -> orden.aplicar(EstadoOrden.RECHAZADA, 100, 0, null, null, AHORA))
                .isInstanceOf(EventoImposibleException.class);
    }

    @Test
    void conLaReservaAceptadaElComandoLlevaLaOrdenCompletaYSiempreElMismoId() {
        ComandoOrden comando = orden.comandoParaElMotor();

        assertThat(comando.accion()).isEqualTo(AccionComando.NUEVA);
        assertThat(comando.ordenId()).isEqualTo(orden.getId());
        assertThat(comando.usuarioId()).isEqualTo(orden.getUsuarioId().toString());
        assertThat(comando.simbolo()).isEqualTo("ANDX");
        assertThat(comando.lado()).isEqualTo(Lado.COMPRA);
        assertThat(comando.precioLimite()).isEqualTo(5_000L);
        assertThat(comando.cantidad()).isEqualTo(100);
        // Si la aceptación se procesara dos veces, el motor recibiría el mismo comandoId y lo ignoraría
        assertThat(orden.comandoParaElMotor().comandoId()).isEqualTo(comando.comandoId());
    }

    @Test
    void unaOrdenQueYaNoEstaPendienteNoVuelveAlMotor() {
        orden.aplicar(EstadoOrden.ABIERTA, 100, 0, null, null, AHORA);

        assertThatThrownBy(orden::comandoParaElMotor)
                .isInstanceOf(EventoImposibleException.class)
                .hasMessage("la reserva se aceptó, pero la orden ya está ABIERTA");
    }

    @Test
    void conLaReservaRechazadaQuedaRechazadaConElMotivo() {
        orden.rechazar("Saldo insuficiente: tienes 1.000 COP disponibles.", AHORA.plusSeconds(1));

        assertThat(orden.getEstado()).isEqualTo(EstadoOrden.RECHAZADA);
        assertThat(orden.getMotivo()).isEqualTo("Saldo insuficiente: tienes 1.000 COP disponibles.");
        assertThat(orden.getActualizadaEn()).isEqualTo(AHORA.plusSeconds(1));
        // Terminó: ni el motor ni otra respuesta de la reserva la cambian
        assertThatThrownBy(() -> orden.aplicar(EstadoOrden.ABIERTA, 100, 0, null, null, AHORA))
                .isInstanceOf(EventoImposibleException.class);
        assertThatThrownBy(orden::comandoParaElMotor).isInstanceOf(EventoImposibleException.class);
    }

    @Test
    void unaOrdenQueElMotorYaTieneNoSePuedeRechazar() {
        orden.aplicar(EstadoOrden.PARCIAL, 100, 40, BigDecimal.TEN, null, AHORA);

        assertThatThrownBy(() -> orden.rechazar("Saldo insuficiente", AHORA))
                .isInstanceOf(EventoImposibleException.class)
                .hasMessage("la reserva se rechazó, pero la orden ya está PARCIAL");
        assertThat(orden.getEstado()).isEqualTo(EstadoOrden.PARCIAL);
    }
}
