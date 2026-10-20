package co.bolsasim.marketdata.servicio;

import co.bolsasim.marketdata.instrumento.Instrumento;
import co.bolsasim.marketdata.instrumento.RepositorioInstrumentos;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Al arrancar vuelve a publicar todo el catálogo en instruments.events. Así los demás servicios conocen
 * los 10 instrumentos iniciales (que crea una migración de Flyway, no la API) y se repara cualquier
 * evento que se haya perdido con Kafka caído.
 *
 * <p>Publicar dos veces lo mismo no hace daño: el evento lleva el estado completo y quien lo recibe
 * simplemente reemplaza su copia.
 */
@Component
public class RepublicadorCatalogo {

    private static final Logger log = LoggerFactory.getLogger(RepublicadorCatalogo.class);

    private final RepositorioInstrumentos repositorio;
    private final PublicadorInstrumentos publicador;

    public RepublicadorCatalogo(RepositorioInstrumentos repositorio, PublicadorInstrumentos publicador) {
        this.repositorio = repositorio;
        this.publicador = publicador;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void republicar() {
        try {
            List<Instrumento> catalogo = repositorio.findAll();
            catalogo.forEach(instrumento -> publicador.publicar(instrumento.comoEvento()));
            log.info("Catálogo republicado en instruments.events: {} instrumentos", catalogo.size());
        } catch (RuntimeException e) {
            // Sin Kafka el servicio igual atiende la API; el catálogo se republica en el próximo arranque
            log.error("No se pudo republicar el catálogo en instruments.events", e);
        }
    }
}
