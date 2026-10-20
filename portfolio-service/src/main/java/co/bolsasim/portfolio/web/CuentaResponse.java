package co.bolsasim.portfolio.web;

import co.bolsasim.portfolio.cuenta.Cuenta;
import java.time.Instant;

/**
 * El saldo de la cuenta (RF-08), en COP enteros. saldoTotal es disponible + reservado: todo el dinero. El valor
 * de las acciones se suma al portafolio con HU-11.
 */
public record CuentaResponse(
        long saldoDisponible,
        long saldoReservado,
        long saldoTotal,
        long saldoInicial,
        Instant actualizadoEn) {

    public static CuentaResponse de(Cuenta cuenta) {
        return new CuentaResponse(cuenta.saldoDisponible(), cuenta.saldoReservado(), cuenta.saldoTotal(),
                cuenta.saldoInicial(), cuenta.actualizadoEn());
    }
}
