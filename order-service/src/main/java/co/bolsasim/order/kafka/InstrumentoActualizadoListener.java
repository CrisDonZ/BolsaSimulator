package co.bolsasim.order.kafka;

import co.bolsasim.eventos.InstrumentoActualizado;
import co.bolsasim.eventos.Topicos;
import co.bolsasim.order.instrumento.Instrumento;
import co.bolsasim.order.instrumento.RepositorioInstrumentos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Escucha instruments.events y mantiene la copia local de los instrumentos. Así un instrumento creado o
 * suspendido se nota en las órdenes sin reiniciar nada (RNF-20).
 *
 * <p>Cada evento trae el estado completo, así que basta con reemplazar la copia: si llega dos veces, el
 * resultado es el mismo. Uno más viejo que la copia se ignora, para que un mensaje atrasado no reactive
 * un instrumento que ya se suspendió.
 */
@Component
public class InstrumentoActualizadoListener {

    private static final Logger log = LoggerFactory.getLogger(InstrumentoActualizadoListener.class);

    private final RepositorioInstrumentos repositorio;

    public InstrumentoActualizadoListener(RepositorioInstrumentos repositorio) {
        this.repositorio = repositorio;
    }

    @KafkaListener(topics = Topicos.INSTRUMENTS_EVENTS, groupId = "order-service")
    @Transactional
    public void recibir(InstrumentoActualizado evento) {
        var copia = repositorio.findById(evento.simbolo());
        if (copia.isEmpty()) {
            repositorio.save(new Instrumento(evento));
            log.info("Instrumento {} guardado en la copia local ({}): ya puede recibir órdenes si está activo",
                    evento.simbolo(), evento.estado());
            return;
        }

        Instrumento instrumento = copia.get();
        if (evento.actualizadoEn().isBefore(instrumento.getActualizadoEn())) {
            log.info("Evento de {} más viejo que la copia local; ignorado", evento.simbolo());
            return;
        }
        boolean cambioDeEstado = instrumento.getEstado() != evento.estado();
        instrumento.actualizar(evento);
        if (cambioDeEstado) {
            log.info("Instrumento {} ahora está {} en la copia local", evento.simbolo(), evento.estado());
        } else {
            log.info("Instrumento {} actualizado en la copia local ({})", evento.simbolo(), evento.estado());
        }
    }
}
