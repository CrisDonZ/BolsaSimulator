package co.bolsasim.portfolio.servicio;

import co.bolsasim.portfolio.cuenta.Cuenta;
import java.util.List;

/** El dinero de la cuenta y sus acciones valoradas a precio actual (RF-32, RF-33). */
public record Portafolio(Cuenta cuenta, List<PosicionValorada> posiciones) {

    /** Lo que valen hoy todas las acciones. */
    public long valorAcciones() {
        return posiciones.stream().mapToLong(PosicionValorada::valorActual).reduce(0, Math::addExact);
    }

    /** Todo lo que tiene el usuario: saldo total (disponible + reservado) más el valor de sus acciones. */
    public long valorTotal() {
        return Math.addExact(cuenta.saldoTotal(), valorAcciones());
    }
}
