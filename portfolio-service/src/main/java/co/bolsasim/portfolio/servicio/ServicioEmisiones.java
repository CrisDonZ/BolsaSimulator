package co.bolsasim.portfolio.servicio;

import co.bolsasim.eventos.InstrumentoActualizado;
import co.bolsasim.portfolio.cuenta.RepositorioCuentas;
import co.bolsasim.portfolio.emision.Emision;
import co.bolsasim.portfolio.emision.RepositorioEmisiones;
import co.bolsasim.portfolio.movimiento.RegistroMovimientos;
import co.bolsasim.portfolio.movimiento.TipoMovimiento;
import co.bolsasim.portfolio.posicion.RepositorioPosiciones;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La oferta inicial (RF-59): todas las acciones emitidas de cada instrumento van a la cuenta del creador de
 * mercado, con costo promedio igual al precio inicial. Es el único lugar donde aparecen acciones; después solo
 * cambian de dueño (RNF-08).
 *
 * <ul>
 *   <li>Se emite una sola vez por símbolo, la primera vez que llega su instruments.events: las ediciones, las
 *       suspensiones y los eventos repetidos no emiten de nuevo (la tabla EMISION tiene el símbolo como llave).</li>
 *   <li>Si el instrumento llega antes que la cuenta del creador, la emisión queda PENDIENTE y se aplica cuando
 *       llega su users.registered. Nunca se pierde.</li>
 * </ul>
 */
@Service
public class ServicioEmisiones {

    private static final Logger log = LoggerFactory.getLogger(ServicioEmisiones.class);

    private final RepositorioEmisiones emisiones;
    private final RepositorioCuentas cuentas;
    private final RepositorioPosiciones posiciones;
    private final RegistroMovimientos movimientos;

    public ServicioEmisiones(RepositorioEmisiones emisiones, RepositorioCuentas cuentas,
                             RepositorioPosiciones posiciones, RegistroMovimientos movimientos) {
        this.emisiones = emisiones;
        this.cuentas = cuentas;
        this.posiciones = posiciones;
        this.movimientos = movimientos;
    }

    /**
     * Emite las acciones del instrumento si es la primera vez que llega.
     *
     * @return false si el símbolo ya se había emitido (o quedó pendiente): no cambia nada
     */
    @Transactional
    public boolean emitir(InstrumentoActualizado instrumento) {
        emisiones.esperarTurno();
        if (emisiones.existe(instrumento.simbolo())) {
            return false;
        }
        Emision emision = new Emision(instrumento.simbolo(), instrumento.accionesEmitidas(),
                instrumento.precioInicial());
        Instant ahora = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Optional<UUID> creador = cuentas.creadorDeMercado();
        if (creador.isEmpty()) {
            emisiones.guardarPendiente(emision, ahora);
            log.info("Emisión de {} ({} acciones) PENDIENTE: todavía no existe la cuenta del creador de mercado",
                    emision.simbolo(), emision.accionesEmitidas());
            return true;
        }
        acreditar(emision, creador.get(), ahora);
        emisiones.guardarAplicada(emision, creador.get(), ahora);
        return true;
    }

    /**
     * Marca la cuenta del usuario como la del creador de mercado y le aplica las emisiones pendientes. La cuenta ya
     * debe existir.
     *
     * @return false si otra cuenta ya es el creador de mercado: esta no recibe nada
     */
    @Transactional
    public boolean registrarCreador(UUID usuarioId) {
        emisiones.esperarTurno();
        if (!cuentas.marcarCreadorDeMercado(usuarioId)) {
            log.error("El usuario {} llega como creador de mercado, pero ya hay otro: no recibe las acciones emitidas",
                    usuarioId);
            return false;
        }
        UUID cuentaId = cuentas.creadorDeMercado().orElseThrow();
        Instant ahora = Instant.now().truncatedTo(ChronoUnit.MICROS);
        List<Emision> pendientes = emisiones.pendientes();
        for (Emision emision : pendientes) {
            acreditar(emision, cuentaId, ahora);
            emisiones.marcarAplicada(emision.simbolo(), cuentaId, ahora);
        }
        log.info("Cuenta del creador de mercado lista para el usuario {}: {} emisiones pendientes aplicadas",
                usuarioId, pendientes.size());
        return true;
    }

    private void acreditar(Emision emision, UUID cuentaId, Instant ahora) {
        posiciones.acreditar(cuentaId, emision.simbolo(), emision.accionesEmitidas(), emision.precioInicial());
        // Monto 0: no entra ni sale dinero, solo aparecen las acciones
        movimientos.registrar(cuentaId, TipoMovimiento.EMISION, 0, emision.simbolo(), emision.accionesEmitidas(),
                null, ahora);
        log.info("Emisión de {}: {} acciones a {} COP para el creador de mercado", emision.simbolo(),
                emision.accionesEmitidas(), emision.precioInicial());
    }
}
