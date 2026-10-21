package co.bolsasim.portfolio.servicio;

import co.bolsasim.eventos.EjecucionOrden;
import co.bolsasim.eventos.EventoOrden;
import co.bolsasim.portfolio.cuenta.RepositorioCuentas;
import co.bolsasim.portfolio.kafka.EventosProcesados;
import co.bolsasim.portfolio.movimiento.RegistroMovimientos;
import co.bolsasim.portfolio.movimiento.TipoMovimiento;
import co.bolsasim.portfolio.posicion.RepositorioPosiciones;
import co.bolsasim.portfolio.reserva.EstadoReserva;
import co.bolsasim.portfolio.reserva.RepositorioReservas;
import co.bolsasim.portfolio.reserva.Reserva;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Liquida las compras con lo que publica el motor en orders.events (RF-30, RF-31): cada ejecución se paga con la
 * reserva de su orden y le suma las acciones al comprador; lo que no se usó vuelve al disponible.
 *
 * <ul>
 *   <li>Compra LIMITE: en cada ejecución se libera lo que se pagó por debajo del límite, (límite − precio) ×
 *       acciones. Así lo reservado siempre es justo lo que falta por comprar al precio límite.</li>
 *   <li>Cuando la orden termina (EJECUTADA o CANCELADA), se libera lo que quede: el sobrante de una compra a
 *       mercado o lo que no se ejecutó de una orden cancelada.</li>
 * </ul>
 *
 * <p>Se liquida con orders.events y no con trades.executed porque ahí llegan, en el orden del motor y por la misma
 * partición, las ejecuciones de una orden y su cancelación: la cancelación nunca libera dinero que una ejecución
 * anterior todavía no pagó.
 *
 * <p>Cada evento en una transacción: la marca, el saldo, la posición, la reserva y los movimientos se guardan
 * juntos o no se guarda nada. Un evento repetido no hace nada (RNF-09).
 */
@Service
public class ServicioLiquidacion {

    private static final Logger log = LoggerFactory.getLogger(ServicioLiquidacion.class);

    private final EventosProcesados procesados;
    private final RepositorioReservas reservas;
    private final RepositorioCuentas cuentas;
    private final RepositorioPosiciones posiciones;
    private final RegistroMovimientos movimientos;

    public ServicioLiquidacion(EventosProcesados procesados, RepositorioReservas reservas, RepositorioCuentas cuentas,
                               RepositorioPosiciones posiciones, RegistroMovimientos movimientos) {
        this.procesados = procesados;
        this.reservas = reservas;
        this.cuentas = cuentas;
        this.posiciones = posiciones;
        this.movimientos = movimientos;
    }

    /**
     * @return false si el evento ya se había aplicado o su orden no tiene reserva de dinero
     * @throws EventoImposibleException si el evento no tiene sentido para la reserva; no se aplica nada
     */
    @Transactional
    public boolean aplicar(EventoOrden evento) {
        if (!procesados.marcar(evento.eventoId())) {
            log.info("Evento {} de la orden {} repetido: ya se había liquidado", evento.eventoId(), evento.ordenId());
            return false;
        }
        Optional<Reserva> encontrada = reservas.bloquearPorOrden(evento.ordenId());
        if (encontrada.isEmpty()) {
            // Una venta (su liquidación llega con HU-06) o una compra de antes de las reservas
            log.info("Orden {} sin reserva de dinero: no hay nada que liquidar", evento.ordenId());
            return false;
        }
        Reserva reserva = encontrada.get();
        if (!reserva.usuarioId().toString().equals(evento.usuarioId())) {
            throw new EventoImposibleException("el evento es de otro usuario");
        }
        if (reserva.estado() != EstadoReserva.ACTIVA) {
            throw new EventoImposibleException("la reserva ya terminó (" + reserva.estado() + ")");
        }
        EjecucionOrden ejecucion = evento.ejecucion();
        if (ejecucion == null && !evento.estado().terminado()) {
            // ABIERTA: la orden entró al libro, no hay nada que pagar ni liberar
            return true;
        }

        Instant ahora = Instant.now().truncatedTo(ChronoUnit.MICROS);
        long pendiente = reserva.pendiente();
        long delReservado = 0;
        long delDisponible = 0;
        long liberado = 0;
        if (ejecucion != null) {
            long costo = costo(reserva, ejecucion);
            delReservado = Math.min(costo, pendiente);
            // Solo en una compra a mercado que se ejecuta por encima de lo reservado; lo evitará la banda de ±10 %
            // del motor (RF-26, HU-07)
            delDisponible = costo - delReservado;
            if (reserva.precioLimite() != null) {
                long porDebajoDelLimite = (reserva.precioLimite() - ejecucion.precio()) * ejecucion.cantidad();
                liberado = Math.min(porDebajoDelLimite, pendiente - delReservado);
            }
        }
        if (evento.estado().terminado()) {
            liberado = pendiente - delReservado;
        }

        if (!cuentas.pagarYLiberar(reserva.cuentaId(), delReservado, delDisponible, liberado, ahora)) {
            throw new EventoImposibleException("la compra cuesta " + (delReservado + delDisponible)
                    + " COP, la reserva solo cubría " + delReservado + " y el disponible no alcanza para el resto");
        }
        if (ejecucion != null) {
            posiciones.acreditarCompra(reserva.cuentaId(), evento.simbolo(), ejecucion.cantidad(), ejecucion.precio());
            movimientos.registrar(reserva.cuentaId(), TipoMovimiento.COMPRA, -(delReservado + delDisponible),
                    evento.simbolo(), ejecucion.cantidad(), ejecucion.operacionId(), ahora);
        }
        if (liberado > 0) {
            movimientos.registrar(reserva.cuentaId(), TipoMovimiento.LIBERACION, liberado, evento.simbolo(), null,
                    evento.ordenId(), ahora);
        }

        EstadoReserva estado = EstadoReserva.ACTIVA;
        if (evento.estado().terminado()) {
            estado = reserva.montoConsumido() + delReservado > 0 ? EstadoReserva.CONSUMIDA : EstadoReserva.LIBERADA;
        }
        reservas.registrar(reserva.id(), delReservado, liberado, estado, ahora);

        log.info("Orden {} liquidada ({}): {} COP pagados, {} COP liberados, reserva {}", evento.ordenId(),
                evento.estado(), delReservado + delDisponible, liberado, estado);
        return true;
    }

    /** Lo que cuesta la ejecución. Una compra LIMITE nunca se ejecuta por encima de su límite (RF-24). */
    private static long costo(Reserva reserva, EjecucionOrden ejecucion) {
        if (reserva.precioLimite() != null && ejecucion.precio() > reserva.precioLimite()) {
            throw new EventoImposibleException("se ejecutó a " + ejecucion.precio() + " COP, por encima del límite de "
                    + reserva.precioLimite());
        }
        return Math.multiplyExact(ejecucion.precio(), (long) ejecucion.cantidad());
    }
}
