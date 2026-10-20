package co.bolsasim.portfolio.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.bolsasim.portfolio.cuenta.Cuenta;
import co.bolsasim.portfolio.cuenta.RepositorioCuentas;
import co.bolsasim.portfolio.movimiento.Cursor;
import co.bolsasim.portfolio.movimiento.Movimiento;
import co.bolsasim.portfolio.movimiento.RegistroMovimientos;
import co.bolsasim.portfolio.movimiento.TipoMovimiento;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Prueba unitaria: los repositorios son dobles de Mockito; se prueban las reglas y los filtros. */
class ServicioCuentasTest {

    private static final UUID ANA = UUID.randomUUID();
    private static final UUID CUENTA = UUID.randomUUID();

    private final RepositorioCuentas cuentas = mock(RepositorioCuentas.class);
    private final RegistroMovimientos movimientos = mock(RegistroMovimientos.class);
    private final ServicioCuentas servicio = new ServicioCuentas(cuentas, movimientos, 10_000_000);

    private final Cuenta deAna = new Cuenta(CUENTA, ANA, 10_000_000, 10_000_000, 0, Instant.now());

    @Test
    void abrirUnaCuentaNuevaRegistraElSaldoInicialComoMovimiento() {
        when(cuentas.crearSiNoExiste(eq(ANA), eq(10_000_000L), any())).thenReturn(Optional.of(CUENTA));
        when(cuentas.buscarPorUsuario(ANA)).thenReturn(Optional.of(deAna));

        Cuenta cuenta = servicio.abrir(ANA);

        assertThat(cuenta.saldoDisponible()).isEqualTo(10_000_000);
        verify(movimientos).registrar(eq(CUENTA), eq(TipoMovimiento.SALDO_INICIAL), eq(10_000_000L), isNull(),
                isNull(), isNull(), any());
    }

    @Test
    void siLaCuentaYaExisteNoRegistraOtroSaldoInicial() {
        when(cuentas.crearSiNoExiste(eq(ANA), anyLong(), any())).thenReturn(Optional.empty());
        when(cuentas.buscarPorUsuario(ANA)).thenReturn(Optional.of(deAna));

        servicio.abrir(ANA);

        verifyNoInteractions(movimientos);
    }

    @Test
    void consultarUnaCuentaQueYaExisteNoLaVuelveACrear() {
        when(cuentas.buscarPorUsuario(ANA)).thenReturn(Optional.of(deAna));

        assertThat(servicio.consultar(ANA, "INVERSIONISTA")).isEqualTo(deAna);
        verify(cuentas, never()).crearSiNoExiste(any(), anyLong(), any());
    }

    @Test
    void consultarSinCuentaLaAbreEnElMomento() {
        when(cuentas.buscarPorUsuario(ANA)).thenReturn(Optional.empty(), Optional.of(deAna));
        when(cuentas.crearSiNoExiste(eq(ANA), anyLong(), any())).thenReturn(Optional.of(CUENTA));

        assertThat(servicio.consultar(ANA, "BOT")).isEqualTo(deAna);
        verify(movimientos).registrar(eq(CUENTA), eq(TipoMovimiento.SALDO_INICIAL), eq(10_000_000L), any(), any(),
                any(), any());
    }

    @Test
    void unAdminNoTieneCuentaYNoSeLeAbre() {
        assertThatThrownBy(() -> servicio.consultar(ANA, "ADMIN"))
                .isInstanceOf(SinCuentaException.class)
                .hasMessage("Los administradores no tienen cuenta de inversión.");
        verifyNoInteractions(cuentas, movimientos);
    }

    @Test
    void lasFechasSonDiasEnHoraDeColombiaYHastaIncluyeTodoElDia() {
        when(cuentas.buscarPorUsuario(ANA)).thenReturn(Optional.of(deAna));

        servicio.movimientos(ANA, "INVERSIONISTA", "compra", "2026-10-01", "2026-10-20", 20, null);

        // Colombia es UTC-5: el 1 de octubre empieza a las 05:00 UTC, y "hasta el 20" llega al comienzo del 21
        verify(movimientos).buscar(CUENTA, TipoMovimiento.COMPRA, Instant.parse("2026-10-01T05:00:00Z"),
                Instant.parse("2026-10-21T05:00:00Z"), null, 21);
    }

    @Test
    void siHayMasMovimientosQueElTamanoLaPaginaTraeElCursorDelUltimo() {
        when(cuentas.buscarPorUsuario(ANA)).thenReturn(Optional.of(deAna));
        List<Movimiento> tres = IntStream.range(0, 3)
                .mapToObj(i -> new Movimiento(UUID.randomUUID(), CUENTA, TipoMovimiento.COMPRA, -1_000, "ANDX", 1,
                        UUID.randomUUID(), Instant.now().minusSeconds(i)))
                .toList();
        when(movimientos.buscar(CUENTA, null, null, null, null, 3)).thenReturn(tres);

        PaginaMovimientos pagina = servicio.movimientos(ANA, "INVERSIONISTA", null, null, null, 2, null);

        assertThat(pagina.movimientos()).containsExactly(tres.get(0), tres.get(1));
        assertThat(pagina.siguiente()).isEqualTo(Cursor.despuesDe(tres.get(1)));
    }

    @Test
    void parametrosInvalidosResponden400SinConsultar() {
        String valido = "INVERSIONISTA";
        assertThatThrownBy(() -> servicio.movimientos(ANA, valido, "REGALO", null, null, 20, null))
                .isInstanceOf(ConsultaInvalidaException.class)
                .hasMessage("tipo debe ser uno de: SALDO_INICIAL, RESERVA, LIBERACION, COMPRA, VENTA, REINICIO.");
        assertThatThrownBy(() -> servicio.movimientos(ANA, valido, null, "20-10-2026", null, 20, null))
                .isInstanceOf(ConsultaInvalidaException.class)
                .hasMessageStartingWith("desde debe ser una fecha AAAA-MM-DD");
        assertThatThrownBy(() -> servicio.movimientos(ANA, valido, null, "2026-10-20", "2026-10-01", 20, null))
                .isInstanceOf(ConsultaInvalidaException.class)
                .hasMessage("desde no puede ser posterior a hasta.");
        assertThatThrownBy(() -> servicio.movimientos(ANA, valido, null, null, null, 101, null))
                .isInstanceOf(ConsultaInvalidaException.class)
                .hasMessage("tamano debe estar entre 1 y 100.");
        assertThatThrownBy(() -> servicio.movimientos(ANA, valido, null, null, null, 20, "alterado"))
                .isInstanceOf(ConsultaInvalidaException.class)
                .hasMessageStartingWith("despues no es válido");
        verifyNoInteractions(cuentas, movimientos);
    }
}
