package co.bolsasim.portfolio.servicio;

import co.bolsasim.portfolio.cuenta.Cuenta;
import co.bolsasim.portfolio.posicion.RepositorioPosiciones;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El portafolio de cada usuario (RF-32, RF-33): su saldo y sus acciones valoradas al último precio. El valor se
 * calcula en cada consulta; la actualización en vivo sin recargar llega con el WebSocket.
 */
@Service
public class ServicioPortafolio {

    private final ServicioCuentas cuentas;
    private final RepositorioPosiciones posiciones;

    public ServicioPortafolio(ServicioCuentas cuentas, RepositorioPosiciones posiciones) {
        this.cuentas = cuentas;
        this.posiciones = posiciones;
    }

    /**
     * El portafolio del usuario del token. Como la consulta del saldo, abre la cuenta si todavía no tiene, y un
     * ADMIN recibe {@link SinCuentaException}.
     *
     * <p>El saldo y las posiciones son dos lecturas (READ COMMITTED): si una liquidación confirma justo entre las
     * dos, el valor total de esa respuesta queda corrido por esa operación, y la siguiente consulta ya cuadra. Para
     * mostrar en pantalla basta; la conciliación (regla 4) no usa esta consulta.
     *
     * @param rol el claim "rol" del token
     */
    @Transactional
    public Portafolio consultar(UUID usuarioId, String rol) {
        Cuenta cuenta = cuentas.consultar(usuarioId, rol);
        return new Portafolio(cuenta, posiciones.deLaCuenta(cuenta.id()).stream().map(PosicionValorada::de).toList());
    }
}
