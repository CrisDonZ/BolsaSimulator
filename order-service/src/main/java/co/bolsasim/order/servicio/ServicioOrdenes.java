package co.bolsasim.order.servicio;

import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.TipoOrden;
import co.bolsasim.eventos.Topicos;
import co.bolsasim.order.instrumento.Instrumento;
import co.bolsasim.order.instrumento.RepositorioInstrumentos;
import co.bolsasim.order.orden.ConsultaOrdenes;
import co.bolsasim.order.orden.Cursor;
import co.bolsasim.order.orden.FiltroEstado;
import co.bolsasim.order.orden.Orden;
import co.bolsasim.order.orden.RepositorioOrdenes;
import co.bolsasim.order.outbox.Outbox;
import co.bolsasim.order.usuario.RepositorioUsuarios;
import co.bolsasim.order.web.CrearOrdenRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reglas de negocio de las órdenes. No sabe nada de Kafka: guarda la orden y deja su comando en el outbox,
 * en la misma transacción; {@link co.bolsasim.order.outbox.PublicadorOutbox} lo lleva al motor después.
 */
@Service
public class ServicioOrdenes {

    /** Tamaño de página si el cliente no lo dice, y el máximo que acepta. */
    public static final int TAMANO_POR_DEFECTO = 20;
    public static final int TAMANO_MAXIMO = 100;

    private final RepositorioOrdenes ordenes;
    private final ConsultaOrdenes consulta;
    private final Outbox outbox;
    private final RepositorioUsuarios usuarios;
    private final RepositorioInstrumentos instrumentos;

    public ServicioOrdenes(RepositorioOrdenes ordenes, ConsultaOrdenes consulta, Outbox outbox,
                           RepositorioUsuarios usuarios, RepositorioInstrumentos instrumentos) {
        this.ordenes = ordenes;
        this.consulta = consulta;
        this.outbox = outbox;
        this.usuarios = usuarios;
        this.instrumentos = instrumentos;
    }

    /**
     * Guarda la orden PENDIENTE y su comando NUEVA en el outbox, en la misma transacción (RNF-12): si Kafka
     * está caído, la orden igual queda y su comando sale cuando Kafka vuelva. Si una validación falla, no se
     * guarda nada: nunca llegó a ser una orden.
     *
     * @param usuarioId el "sub" del token de acceso: quien pone la orden es quien inició sesión
     */
    @Transactional
    public Orden crear(String usuarioId, CrearOrdenRequest request) {
        validarPrecio(request);
        UUID usuario = validarUsuario(usuarioId);
        validarInstrumento(request.simbolo().toUpperCase(Locale.ROOT));

        ComandoOrden comando = ComandoOrden.nueva(
                usuarioId,
                request.simbolo(),
                request.lado(),
                request.tipo(),
                request.precioLimite(),
                request.cantidad());

        Orden orden = ordenes.save(Orden.nueva(comando, usuario, Instant.now().truncatedTo(ChronoUnit.MICROS)));
        // La clave es el símbolo, como antes: el motor recibe las órdenes de ANDX en el orden en que llegaron
        outbox.guardar(Topicos.ORDERS_COMMANDS, comando.simbolo(), comando);
        return orden;
    }

    /** La orden de otro usuario responde lo mismo que una que no existe (RNF-23). */
    @Transactional(readOnly = true)
    public Orden buscar(String usuarioId, UUID ordenId) {
        return comoUuid(usuarioId)
                .flatMap(usuario -> ordenes.findByIdAndUsuarioId(ordenId, usuario))
                .orElseThrow(() -> new OrdenNoEncontradaException(ordenId));
    }

    /**
     * Una página de las órdenes del usuario, de la más reciente a la más vieja.
     *
     * @param estado  ABIERTAS, uno de los estados, o null para todas
     * @param tamano  cuántas órdenes por página, de 1 a {@value #TAMANO_MAXIMO}
     * @param despues el cursor "siguiente" de la página anterior; null para la primera
     */
    @Transactional(readOnly = true)
    public PaginaOrdenes listar(String usuarioId, String estado, int tamano, String despues) {
        if (tamano < 1 || tamano > TAMANO_MAXIMO) {
            throw new ConsultaInvalidaException("tamano debe estar entre 1 y " + TAMANO_MAXIMO + ".");
        }
        FiltroEstado filtro = filtro(estado);
        Cursor cursor = cursor(despues);
        Optional<UUID> usuario = comoUuid(usuarioId);
        if (usuario.isEmpty()) {
            return new PaginaOrdenes(List.of(), null);
        }

        // Una fila de más dice si hay otra página, sin contar todas las órdenes del usuario
        List<Orden> filas = consulta.buscar(usuario.get(), filtro, cursor, tamano + 1);
        if (filas.size() <= tamano) {
            return new PaginaOrdenes(filas, null);
        }
        List<Orden> pagina = filas.subList(0, tamano);
        return new PaginaOrdenes(pagina, Cursor.despuesDe(pagina.getLast()));
    }

    private static FiltroEstado filtro(String estado) {
        try {
            return FiltroEstado.de(estado);
        } catch (IllegalArgumentException e) {
            throw new ConsultaInvalidaException("estado debe ser uno de: " + FiltroEstado.VALORES + ".");
        }
    }

    private static Cursor cursor(String despues) {
        if (despues == null || despues.isBlank()) {
            return null;
        }
        try {
            return Cursor.decodificar(despues.trim());
        } catch (IllegalArgumentException e) {
            throw new ConsultaInvalidaException(
                    "despues no es válido: usa el valor \"siguiente\" de la página anterior, sin cambiarlo.");
        }
    }

    /**
     * El usuario debe estar en la copia local que llena users.registered: puede iniciar sesión unos
     * milisegundos antes de que llegue su evento. Un id que ni siquiera es un UUID tampoco puede ser
     * un usuario registrado.
     */
    private UUID validarUsuario(String usuarioId) {
        UUID id = comoUuid(usuarioId).orElseThrow(() -> new UsuarioNoRegistradoException(usuarioId));
        if (!usuarios.existsById(id)) {
            throw new UsuarioNoRegistradoException(usuarioId);
        }
        return id;
    }

    private static Optional<UUID> comoUuid(String usuarioId) {
        try {
            return Optional.of(UUID.fromString(usuarioId));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * El instrumento debe estar en la copia local que llena instruments.events y estar activo. Uno
     * suspendido rechaza órdenes nuevas; las cancelaciones, cuando existan (HU-08), sí pasarán (RF-29).
     */
    private void validarInstrumento(String simbolo) {
        Instrumento instrumento = instrumentos.findById(simbolo)
                .orElseThrow(() -> InstrumentoNoDisponibleException.noExiste(simbolo));
        if (!instrumento.activo()) {
            throw InstrumentoNoDisponibleException.suspendido(simbolo);
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
