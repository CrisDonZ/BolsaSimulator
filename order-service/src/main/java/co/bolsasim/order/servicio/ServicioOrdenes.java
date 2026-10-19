package co.bolsasim.order.servicio;

import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.TipoOrden;
import co.bolsasim.order.usuario.RepositorioUsuarios;
import co.bolsasim.order.web.CrearOrdenRequest;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Reglas de negocio de una orden nueva. No sabe nada de Kafka: solo valida y
 * le pasa el comando al publicador. Eso permite probarlo sin levantar Kafka.
 */
@Service
public class ServicioOrdenes {

    private final PublicadorOrdenes publicador;
    private final RepositorioUsuarios usuarios;

    public ServicioOrdenes(PublicadorOrdenes publicador, RepositorioUsuarios usuarios) {
        this.publicador = publicador;
        this.usuarios = usuarios;
    }

    /**
     * @param usuarioId el "sub" del token de acceso: quien pone la orden es quien inició sesión
     */
    public ComandoOrden crear(String usuarioId, CrearOrdenRequest request) {
        validarPrecio(request);
        validarUsuario(usuarioId);

        ComandoOrden comando = ComandoOrden.nueva(
                usuarioId,
                request.simbolo(),
                request.lado(),
                request.tipo(),
                request.precioLimite(),
                request.cantidad());

        publicador.publicar(comando);
        return comando;
    }

    /**
     * El usuario debe estar en la copia local que llena users.registered: puede iniciar sesión unos
     * milisegundos antes de que llegue su evento. Un id que ni siquiera es un UUID tampoco puede ser
     * un usuario registrado.
     */
    private void validarUsuario(String usuarioId) {
        UUID id;
        try {
            id = UUID.fromString(usuarioId);
        } catch (IllegalArgumentException e) {
            throw new UsuarioNoRegistradoException(usuarioId);
        }
        if (!usuarios.existsById(id)) {
            throw new UsuarioNoRegistradoException(usuarioId);
        }
    }

    // Próximos pasos (ver requisitos RF-16 y RF-17): validar tick, banda de ±10 %,
    // mercado abierto, y pedir la reserva de fondos a portfolio-service.
    private void validarPrecio(CrearOrdenRequest request) {
        if (request.tipo() == TipoOrden.LIMITE && request.precioLimite() == null) {
            throw new OrdenInvalidaException("Una orden LIMITE necesita precioLimite.");
        }
        if (request.tipo() == TipoOrden.MERCADO && request.precioLimite() != null) {
            throw new OrdenInvalidaException(
                    "Una orden MERCADO no lleva precioLimite: se ejecuta al mejor precio disponible.");
        }
    }
}
