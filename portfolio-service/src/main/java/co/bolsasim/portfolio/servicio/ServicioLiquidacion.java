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
import co.bolsasim.portfolio.reserva.TipoReserva;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Liquida cada operación con lo que publica el motor en orders.events (RF-30, RF-31), una orden a la vez: cada
 * operación llega como dos eventos, el del comprador y el del vendedor. Es contabilidad de doble partida (RNF-08):
 * lo que paga el comprador lo cobra el vendedor, y las acciones que salen de uno entran al otro.
 *
 * <ul>
 *   <li>Compra (reserva de DINERO): cada ejecución se paga con la reserva y le suma las acciones al comprador. Con
 *       una compra LIMITE se libera en cada ejecución lo que se pagó por debajo del límite, (límite − precio) ×
 *       acciones, así lo reservado siempre es justo lo que falta por comprar al precio límite. Una compra a MERCADO
 *       reservó cantidad × referencia × 1,10 y el motor no la ejecuta por encima de ese 10 % (RF-26): si una
 *       ejecución costara más de lo que queda reservado, el evento no se liquida.</li>
 *   <li>Venta (reserva de ACCIONES): cada ejecución entrega las acciones reservadas y cobra precio × acciones.</li>
 *   <li>Cuando la orden termina (EJECUTADA o CANCELADA), se libera lo que quede: el dinero que sobró de una compra o
 *       las acciones que no se vendieron.</li>
 * </ul>
 *
 * <p>Se liquida con orders.events y no con trades.executed porque ahí llegan, en el orden del motor y por la misma
 * partición, las ejecuciones de una orden y su cancelación: la cancelación nunca libera lo que una ejecución
 * anterior todavía no usó.
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
     * @return false si el evento ya se había aplicado o su orden no tiene reserva
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
            // Una orden de antes de las reservas: no hay nada que pagar con ella
            log.info("Orden {} sin reserva: no hay nada que liquidar", evento.ordenId());
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
        if (reserva.tipo() == TipoReserva.ACCIONES) {
            liquidarVenta(reserva, evento, ahora);
        } else {
            liquidarCompra(reserva, evento, ahora);
        }
        return true;
    }

    /**
     * La compra paga cada ejecución con la reserva, suma las acciones a la posición y libera lo pagado por debajo
     * del límite; al terminar, libera lo que quede.
     */
    private void liquidarCompra(Reserva reserva, EventoOrden evento, Instant ahora) {
        EjecucionOrden ejecucion = evento.ejecucion();
        long pendiente = reserva.pendiente();
        long pagado = 0;
        long liberado = 0;
        if (ejecucion != null) {
            pagado = costo(reserva, ejecucion);
            if (pagado > pendiente) {
                // Con la banda de ±10 % del motor (RF-26) una compra a mercado nunca cuesta más de lo reservado. Si
                // pasa, es un error: no se toma del disponible ni se deja la cuenta en negativo en silencio
                throw new EventoImposibleException("la compra cuesta " + pagado + " COP y a la reserva solo le quedan "
                        + pendiente);
            }
            if (reserva.precioLimite() != null) {
                long porDebajoDelLimite = Math.multiplyExact(reserva.precioLimite() - ejecucion.precio(),
                        (long) ejecucion.cantidad());
                liberado = Math.min(porDebajoDelLimite, pendiente - pagado);
            }
        }
        if (evento.estado().terminado()) {
            liberado = pendiente - pagado;
        }

        if (!cuentas.pagarYLiberar(reserva.cuentaId(), pagado, liberado, ahora)) {
            throw new EventoImposibleException("la cuenta ya no tiene reservados los " + (pagado + liberado) + " COP");
        }
        if (ejecucion != null) {
            posiciones.acreditar(reserva.cuentaId(), evento.simbolo(), ejecucion.cantidad(), ejecucion.precio());
            movimientos.registrar(reserva.cuentaId(), TipoMovimiento.COMPRA, -pagado, evento.simbolo(),
                    (long) ejecucion.cantidad(), ejecucion.operacionId(), ahora);
        }
        if (liberado > 0) {
            movimientos.registrar(reserva.cuentaId(), TipoMovimiento.LIBERACION, liberado, evento.simbolo(), null,
                    evento.ordenId(), ahora);
        }

        EstadoReserva estado = EstadoReserva.ACTIVA;
        if (evento.estado().terminado()) {
            estado = reserva.montoConsumido() + pagado > 0 ? EstadoReserva.CONSUMIDA : EstadoReserva.LIBERADA;
        }
        reservas.registrar(reserva.id(), pagado, liberado, estado, ahora);

        log.info("Compra {} liquidada ({}): {} COP pagados, {} COP liberados, reserva {}", evento.ordenId(),
                evento.estado(), pagado, liberado, estado);
    }

    /**
     * La venta entrega las acciones de cada ejecución, que salen de la posición y de lo reservado, y cobra
     * precio × acciones en el disponible. Al terminar, las reservadas que sobran vuelven a estar libres. El costo
     * promedio no cambia: las acciones que quedan costaron lo mismo.
     */
    private void liquidarVenta(Reserva reserva, EventoOrden evento, Instant ahora) {
        if (!reserva.simbolo().equals(evento.simbolo())) {
            throw new EventoImposibleException("la reserva es de " + reserva.simbolo() + " y el evento de "
                    + evento.simbolo());
        }
        EjecucionOrden ejecucion = evento.ejecucion();
        long pendiente = reserva.pendiente();
        long entregadas = 0;
        if (ejecucion != null) {
            entregadas = ejecucion.cantidad();
            if (entregadas > pendiente) {
                throw new EventoImposibleException("se venderían " + entregadas + " acciones y la reserva solo tiene "
                        + pendiente);
            }
            if (!posiciones.entregar(reserva.cuentaId(), reserva.simbolo(), entregadas)) {
                throw new EventoImposibleException("la posición ya no tiene reservadas las " + entregadas
                        + " acciones");
            }
            long ingreso = Math.multiplyExact(ejecucion.precio(), entregadas);
            cuentas.acreditarVenta(reserva.cuentaId(), ingreso, ahora);
            movimientos.registrar(reserva.cuentaId(), TipoMovimiento.VENTA, ingreso, reserva.simbolo(), entregadas,
                    ejecucion.operacionId(), ahora);
        }

        long liberadas = 0;
        EstadoReserva estado = EstadoReserva.ACTIVA;
        if (evento.estado().terminado()) {
            liberadas = pendiente - entregadas;
            if (liberadas > 0 && !posiciones.liberar(reserva.cuentaId(), reserva.simbolo(), liberadas)) {
                throw new EventoImposibleException("la posición ya no tiene reservadas las " + liberadas
                        + " acciones que sobran");
            }
            estado = reserva.montoConsumido() + entregadas > 0 ? EstadoReserva.CONSUMIDA : EstadoReserva.LIBERADA;
        }
        reservas.registrar(reserva.id(), entregadas, liberadas, estado, ahora);

        log.info("Venta {} liquidada ({}): {} acciones entregadas, {} liberadas, reserva {}", evento.ordenId(),
                evento.estado(), entregadas, liberadas, estado);
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
