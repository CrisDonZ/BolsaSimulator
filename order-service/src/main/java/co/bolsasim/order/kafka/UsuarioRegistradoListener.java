package co.bolsasim.order.kafka;

import co.bolsasim.eventos.Topicos;
import co.bolsasim.eventos.UsuarioRegistrado;
import co.bolsasim.order.usuario.RepositorioUsuarios;
import co.bolsasim.order.usuario.Usuario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Escucha users.registered y guarda el id de cada usuario nuevo en la copia local.
 *
 * <p>Kafka puede entregar el mismo evento dos veces (por ejemplo, tras un reinicio).
 * Como el id es la llave primaria, la segunda vez simplemente se ignora.
 */
@Component
public class UsuarioRegistradoListener {

    private static final Logger log = LoggerFactory.getLogger(UsuarioRegistradoListener.class);

    private final RepositorioUsuarios repositorio;

    public UsuarioRegistradoListener(RepositorioUsuarios repositorio) {
        this.repositorio = repositorio;
    }

    @KafkaListener(topics = Topicos.USERS_REGISTERED, groupId = "order-service")
    public void recibir(UsuarioRegistrado evento) {
        if (repositorio.existsById(evento.usuarioId())) {
            log.info("Usuario {} ya estaba en la copia local; evento repetido ignorado", evento.usuarioId());
            return;
        }
        repositorio.save(new Usuario(evento.usuarioId(), evento.registradoEn()));
        log.info("Usuario {} guardado en la copia local: ya puede enviar órdenes", evento.usuarioId());
    }
}
