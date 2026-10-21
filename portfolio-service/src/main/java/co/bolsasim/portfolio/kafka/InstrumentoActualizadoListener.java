package co.bolsasim.portfolio.kafka;

import co.bolsasim.eventos.InstrumentoActualizado;
import co.bolsasim.eventos.Topicos;
import co.bolsasim.portfolio.servicio.ServicioEmisiones;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Escucha instruments.events y emite las acciones de cada instrumento nuevo para el creador de mercado (RF-59). El
 * tópico es compactado y el grupo lo lee desde el principio: al arrancar por primera vez, portfolio-service emite
 * todo el catálogo. No hace falta EVENTO_PROCESADO: la tabla EMISION ya tiene el símbolo como llave, y una edición,
 * una suspensión o el mismo evento otra vez no emiten de nuevo.
 */
@Component
public class InstrumentoActualizadoListener {

    private final ServicioEmisiones emisiones;

    public InstrumentoActualizadoListener(ServicioEmisiones emisiones) {
        this.emisiones = emisiones;
    }

    @KafkaListener(topics = Topicos.INSTRUMENTS_EVENTS, groupId = "portfolio-service")
    public void recibir(InstrumentoActualizado instrumento) {
        emisiones.emitir(instrumento);
    }
}
