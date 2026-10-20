package co.bolsasim.portfolio.cuenta;

import java.time.Instant;
import java.util.UUID;

/**
 * Tabla CUENTA del modelo entidad-relación: el dinero de un usuario, en COP enteros.
 *
 * @param saldoInicial    con lo que empezó; la base para calcular la rentabilidad (ranking)
 * @param saldoDisponible dinero libre para operar
 * @param saldoReservado  dinero bloqueado en órdenes abiertas (llegará con las reservas de HU-05)
 */
public record Cuenta(
        UUID id,
        UUID usuarioId,
        long saldoInicial,
        long saldoDisponible,
        long saldoReservado,
        Instant actualizadoEn) {

    /** Disponible + reservado: todo el dinero de la cuenta. El valor de las acciones se suma con HU-11. */
    public long saldoTotal() {
        return saldoDisponible + saldoReservado;
    }
}
