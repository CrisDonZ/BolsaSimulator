package co.bolsasim.portfolio.kafka;

import co.bolsasim.eventos.Topicos;
import co.bolsasim.eventos.UsuarioRegistrado;
import co.bolsasim.portfolio.servicio.ServicioCuentas;
import co.bolsasim.portfolio.servicio.ServicioEmisiones;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Escucha users.registered y abre la cuenta de cada usuario nuevo con su saldo inicial y su rol (RF-07). Si es el
 * creador de mercado, le acredita además las emisiones que esperaban su cuenta (RF-59).
 *
 * <p>Un evento repetido se ignora (EVENTO_PROCESADO, RNF-09); y aunque llegara uno distinto del mismo usuario, la
 * cuenta es única por usuario. Un ADMIN no recibe cuenta, porque no opera; auth-service tampoco publica el suyo.
 */
@Component
public class UsuarioRegistradoListener {

    private static final Logger log = LoggerFactory.getLogger(UsuarioRegistradoListener.class);

    private final EventosProcesados procesados;
    private final ServicioCuentas cuentas;
    private final ServicioEmisiones emisiones;

    public UsuarioRegistradoListener(EventosProcesados procesados, ServicioCuentas cuentas,
                                     ServicioEmisiones emisiones) {
        this.procesados = procesados;
        this.cuentas = cuentas;
        this.emisiones = emisiones;
    }

    @KafkaListener(topics = Topicos.USERS_REGISTERED, groupId = "portfolio-service")
    @Transactional
    public void recibir(UsuarioRegistrado evento) {
        if (!procesados.marcar(evento.eventoId())) {
            log.info("Evento {} del usuario {} repetido: ya se había aplicado", evento.eventoId(), evento.usuarioId());
            return;
        }
        if ("ADMIN".equals(evento.rol())) {
            log.info("Usuario {} es ADMIN: no recibe cuenta", evento.usuarioId());
            return;
        }
        cuentas.abrir(evento.usuarioId(), evento.rol());
        if (evento.creadorDeMercado()) {
            emisiones.registrarCreador(evento.usuarioId());
        }
    }
}
