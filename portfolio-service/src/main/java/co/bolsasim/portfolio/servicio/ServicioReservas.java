package co.bolsasim.portfolio.servicio;

import co.bolsasim.eventos.Lado;
import co.bolsasim.eventos.ReservaProcesada;
import co.bolsasim.eventos.SolicitudReserva;
import co.bolsasim.eventos.TipoOrden;
import co.bolsasim.eventos.Topicos;
import co.bolsasim.portfolio.cuenta.Cuenta;
import co.bolsasim.portfolio.cuenta.RepositorioCuentas;
import co.bolsasim.portfolio.kafka.EventosProcesados;
import co.bolsasim.portfolio.movimiento.RegistroMovimientos;
import co.bolsasim.portfolio.movimiento.TipoMovimiento;
import co.bolsasim.portfolio.outbox.Outbox;
import co.bolsasim.portfolio.reserva.RepositorioReservas;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La primera mitad de la saga de la reserva (RF-17, RF-18): por cada orden nueva (orders.requested) bloquea el
 * dinero que necesita y responde en reservations.events si la orden puede ir al motor.
 *
 * <p>Todo en una transacción por solicitud: la marca del evento, el saldo, la reserva, su movimiento y la respuesta
 * en el outbox se guardan juntos o no se guarda nada. Las solicitudes de un usuario llegan por una sola partición
 * (clave = usuario), una tras otra, y el saldo se revisa en el mismo UPDATE que lo reserva: dos órdenes nunca
 * gastan el mismo dinero.
 */
@Service
public class ServicioReservas {

    private static final Logger log = LoggerFactory.getLogger(ServicioReservas.class);

    private final EventosProcesados procesados;
    private final ServicioCuentas servicioCuentas;
    private final RepositorioCuentas cuentas;
    private final RepositorioReservas reservas;
    private final RegistroMovimientos movimientos;
    private final Outbox outbox;

    public ServicioReservas(EventosProcesados procesados, ServicioCuentas servicioCuentas, RepositorioCuentas cuentas,
                            RepositorioReservas reservas, RegistroMovimientos movimientos, Outbox outbox) {
        this.procesados = procesados;
        this.servicioCuentas = servicioCuentas;
        this.cuentas = cuentas;
        this.reservas = reservas;
        this.movimientos = movimientos;
        this.outbox = outbox;
    }

    /**
     * Una compra reserva dinero; si el disponible no alcanza, se rechaza con el motivo (RF-18). Una venta se acepta
     * sin reservar nada hasta que lleguen las reservas de acciones (HU-06).
     *
     * @return false si la solicitud ya se había procesado
     */
    @Transactional
    public boolean procesar(SolicitudReserva solicitud) {
        if (!procesados.marcar(solicitud.solicitudId())) {
            log.info("Solicitud {} de la orden {} repetida: ya se había procesado", solicitud.solicitudId(),
                    solicitud.ordenId());
            return false;
        }
        if (reservas.existeParaLaOrden(solicitud.ordenId())) {
            // Otra solicitud de la misma orden: ya tuvo su respuesta, y reservar otra vez bloquearía el doble
            log.warn("La orden {} ya tiene reserva: la solicitud {} se ignora", solicitud.ordenId(),
                    solicitud.solicitudId());
            return false;
        }
        Instant ahora = Instant.now().truncatedTo(ChronoUnit.MICROS);
        // Si su users.registered todavía no llegó, la cuenta se abre ahora, como en la primera consulta
        Cuenta cuenta = servicioCuentas.abrir(UUID.fromString(solicitud.usuarioId()));

        ReservaProcesada respuesta;
        if (solicitud.lado() == Lado.VENTA) {
            respuesta = ReservaProcesada.aceptada(solicitud, 0, ahora);
            log.info("Venta {} aceptada sin reservar acciones: las reservas de acciones llegan con HU-06",
                    solicitud.ordenId());
        } else {
            respuesta = reservarDinero(cuenta, solicitud, ahora);
        }
        outbox.guardar(Topicos.RESERVATIONS_EVENTS, solicitud.usuarioId(), respuesta);
        return true;
    }

    private ReservaProcesada reservarDinero(Cuenta cuenta, SolicitudReserva solicitud, Instant ahora) {
        OptionalLong monto = montoAReservar(solicitud);
        if (monto.isPresent() && cuentas.reservar(cuenta.id(), monto.getAsLong(), ahora)) {
            Long precioLimite = solicitud.tipo() == TipoOrden.LIMITE ? solicitud.precioLimite() : null;
            reservas.crearDeDinero(cuenta.id(), solicitud.ordenId(), precioLimite, monto.getAsLong(), ahora);
            // Sale del disponible y pasa al reservado: el total de la cuenta no cambia
            movimientos.registrar(cuenta.id(), TipoMovimiento.RESERVA, -monto.getAsLong(), solicitud.simbolo(),
                    solicitud.cantidad(), solicitud.ordenId(), ahora);
            log.info("Orden {}: {} COP reservados", solicitud.ordenId(), monto.getAsLong());
            return ReservaProcesada.aceptada(solicitud, monto.getAsLong(), ahora);
        }

        String motivo = "Saldo insuficiente: tienes " + Pesos.formato(cuenta.saldoDisponible()) + " COP disponibles"
                + (monto.isPresent() ? " y la orden necesita " + Pesos.formato(monto.getAsLong()) + " COP." : ".");
        log.info("Orden {} rechazada: {}", solicitud.ordenId(), motivo);
        return ReservaProcesada.rechazada(solicitud, motivo, ahora);
    }

    /**
     * RF-17: una compra LIMITE reserva precio × cantidad; una a mercado, cantidad × último precio × 1,10,
     * redondeado hacia arriba para no quedarse corta.
     *
     * @return vacío si el monto ni siquiera cabe en un long: ninguna cuenta lo tiene
     */
    static OptionalLong montoAReservar(SolicitudReserva solicitud) {
        try {
            if (solicitud.tipo() == TipoOrden.LIMITE) {
                return OptionalLong.of(Math.multiplyExact(solicitud.precioLimite(), (long) solicitud.cantidad()));
            }
            long base = Math.multiplyExact(solicitud.ultimoPrecio(), (long) solicitud.cantidad());
            return OptionalLong.of(Math.addExact(Math.multiplyExact(base, 11L), 9L) / 10);
        } catch (ArithmeticException e) {
            return OptionalLong.empty();
        }
    }
}
