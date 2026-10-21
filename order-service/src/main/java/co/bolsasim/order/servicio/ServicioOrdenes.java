package co.bolsasim.order.servicio;

import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.EstadoOrden;
import co.bolsasim.eventos.SolicitudReserva;
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
import java.util.regex.Pattern;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reglas de negocio de las órdenes. No sabe nada de Kafka: guarda la orden y deja su solicitud de reserva en el
 * outbox, en la misma transacción; {@link co.bolsasim.order.outbox.PublicadorOutbox} la lleva a portfolio-service
 * después. Cuando la reserva se acepta, {@link ServicioReservas} envía la orden al motor.
 */
@Service
public class ServicioOrdenes {

    /** Tamaño de página si el cliente no lo dice, y el máximo que acepta. */
    public static final int TAMANO_POR_DEFECTO = 20;
    public static final int TAMANO_MAXIMO = 100;

    /** Nombre del índice único (usuario_id, clave_idempotencia) de V5__clave_idempotencia.sql. */
    static final String INDICE_CLAVE = "orden_usuario_clave";

    // De 16 a 64 caracteres (la columna es VARCHAR(64)): un UUID tiene 36. El mínimo evita claves como "1",
    // que un usuario repetiría sin querer en dos órdenes distintas
    private static final Pattern FORMATO_CLAVE = Pattern.compile("[A-Za-z0-9_-]{16,64}");

    private final RepositorioOrdenes ordenes;
    private final ConsultaOrdenes consulta;
    private final Outbox outbox;
    private final RepositorioUsuarios usuarios;
    private final RepositorioInstrumentos instrumentos;
    private final TransactionTemplate transaccion;
    private final TransactionTemplate lectura;

    public ServicioOrdenes(RepositorioOrdenes ordenes, ConsultaOrdenes consulta, Outbox outbox,
                           RepositorioUsuarios usuarios, RepositorioInstrumentos instrumentos,
                           PlatformTransactionManager transacciones) {
        this.ordenes = ordenes;
        this.consulta = consulta;
        this.outbox = outbox;
        this.usuarios = usuarios;
        this.instrumentos = instrumentos;
        this.transaccion = new TransactionTemplate(transacciones);
        this.lectura = new TransactionTemplate(transacciones);
        this.lectura.setReadOnly(true);
    }

    /**
     * Guarda la orden PENDIENTE y su solicitud de reserva en el outbox, en la misma transacción (RNF-12): si Kafka
     * está caído, la orden igual queda y su solicitud sale cuando Kafka vuelva. Si una validación falla, no se
     * guarda nada: nunca llegó a ser una orden, y la clave queda libre para reenviarla corregida.
     *
     * <p>Si la misma solicitud ya llegó con esa clave, devuelve la orden original sin crear nada (HU-10). Se
     * busca antes de las reglas de negocio: si el instrumento se suspendió después, el reintento igual recibe
     * su orden, porque no es una orden nueva.
     *
     * <p>Dos solicitudes con la misma clave al mismo tiempo (el doble clic) no se ven entre sí en la búsqueda.
     * Ahí decide el índice único: PostgreSQL hace esperar a la segunda hasta que la primera termina y, si la
     * primera se guardó, la rechaza. Entonces la segunda lee la orden de la primera y responde con ella.
     *
     * @param usuarioId         el "sub" del token de acceso: quien pone la orden es quien inició sesión
     * @param claveIdempotencia el header Idempotency-Key
     */
    public OrdenCreada crear(String usuarioId, String claveIdempotencia, CrearOrdenRequest request) {
        String clave = validarClave(claveIdempotencia);
        validarPrecio(request);
        UUID usuario = comoUuid(usuarioId).orElseThrow(() -> new UsuarioNoRegistradoException(usuarioId));

        try {
            return transaccion.execute(estado -> ordenes.findByUsuarioIdAndClaveIdempotencia(usuario, clave)
                    .map(original -> repetida(original, request))
                    .orElseGet(() -> nueva(usuario, clave, request)));
        } catch (DataIntegrityViolationException e) {
            if (!esLaClaveRepetida(e)) {
                throw e;
            }
            // La otra solicitud con esta clave ya confirmó (por eso falló esta): su orden ya se puede leer
            return lectura.execute(estado -> ordenes.findByUsuarioIdAndClaveIdempotencia(usuario, clave)
                    .map(original -> repetida(original, request))
                    .orElseThrow(() -> e));
        }
    }

    private OrdenCreada nueva(UUID usuario, String clave, CrearOrdenRequest request) {
        validarUsuario(usuario);
        Instrumento instrumento = validarInstrumento(request.simbolo().toUpperCase(Locale.ROOT));
        // RF-16 también pide el mercado abierto: con HU-23 (abrir y cerrar el mercado) se valida aquí. Hasta
        // entonces el mercado está siempre abierto
        long precioDeReferencia = validarPrecioLimite(request, instrumento);

        ComandoOrden comando = ComandoOrden.nueva(
                usuario.toString(),
                request.simbolo(),
                request.lado(),
                request.tipo(),
                request.precioLimite(),
                request.cantidad());

        // saveAndFlush: el INSERT va antes que el outbox, así el choque con otra solicitud de la misma clave
        // se resuelve aquí, antes de escribir la solicitud
        Orden orden = ordenes.saveAndFlush(
                Orden.nueva(comando, usuario, clave, Instant.now().truncatedTo(ChronoUnit.MICROS)));
        // La clave del mensaje es el usuario: portfolio-service procesa sus solicitudes una tras otra, así dos
        // órdenes a la vez no gastan el mismo saldo
        outbox.guardar(Topicos.ORDERS_REQUESTED, usuario.toString(),
                SolicitudReserva.de(comando, precioDeReferencia));
        return new OrdenCreada(orden, false);
    }

    /** La misma clave solo vale para la misma orden: con otro cuerpo es un error del cliente, no un reintento. */
    private static OrdenCreada repetida(Orden original, CrearOrdenRequest request) {
        boolean mismaOrden = original.esLaMismaOrden(request.simbolo().toUpperCase(Locale.ROOT), request.lado(),
                request.tipo(), request.precioLimite(), request.cantidad());
        if (!mismaOrden) {
            throw new ClaveIdempotenciaReusadaException();
        }
        return new OrdenCreada(original, true);
    }

    private static boolean esLaClaveRepetida(DataIntegrityViolationException e) {
        for (Throwable causa = e; causa != null; causa = causa.getCause()) {
            if (causa instanceof ConstraintViolationException violacion) {
                return INDICE_CLAVE.equalsIgnoreCase(violacion.getConstraintName());
            }
        }
        return false;
    }

    /** Acepta la clave sola o entre comillas, que es la forma del borrador de la IETF para este header. */
    private static String validarClave(String claveIdempotencia) {
        if (claveIdempotencia == null || claveIdempotencia.isBlank()) {
            throw new ClaveIdempotenciaInvalidaException("Falta el header Idempotency-Key: envía un identificador "
                    + "único por cada orden, por ejemplo un UUID.");
        }
        String clave = claveIdempotencia.trim();
        if (clave.length() >= 2 && clave.startsWith("\"") && clave.endsWith("\"")) {
            clave = clave.substring(1, clave.length() - 1);
        }
        if (!FORMATO_CLAVE.matcher(clave).matches()) {
            throw new ClaveIdempotenciaInvalidaException("Idempotency-Key debe tener de 16 a 64 letras, números, "
                    + "- o _; por ejemplo un UUID.");
        }
        return clave;
    }

    /**
     * Pide al motor que retire la orden del libro (HU-08, RF-19): deja el comando CANCELAR en el outbox, en la misma
     * transacción que marca la cancelación pedida. El estado no cambia aquí: la orden queda CANCELADA cuando el motor
     * lo confirma en orders.events, y portfolio-service libera lo que quede reservado (RF-31). Una orden PARCIAL
     * conserva lo ejecutado. Si se ejecuta completa antes de que el motor procese el CANCELAR, queda EJECUTADA:
     * cancelar es una solicitud, no una garantía.
     *
     * <p>Solo una orden ABIERTA o PARCIAL: esos estados los pone el motor, así que su NUEVA ya salió y el CANCELAR
     * nunca puede adelantarla. Una PENDIENTE todavía no llega al libro: cancelarla no retiraría nada y la orden
     * entraría después. No se revisa el instrumento: uno suspendido permite cancelar (RF-29).
     *
     * <p>Si la cancelación ya se pidió, devuelve la orden sin publicar otro comando.
     *
     * @throws OrdenNoEncontradaException si no existe o es de otro usuario (RNF-23)
     * @throws OrdenNoCancelableException si está PENDIENTE o ya terminó
     */
    @Transactional
    public Orden cancelar(String usuarioId, UUID ordenId) {
        UUID usuario = comoUuid(usuarioId).orElseThrow(() -> new OrdenNoEncontradaException(ordenId));
        Instant ahora = Instant.now().truncatedTo(ChronoUnit.MICROS);
        boolean marcada = ordenes.solicitarCancelacion(ordenId, usuario, ahora) == 1;
        Orden orden = ordenes.findByIdAndUsuarioId(ordenId, usuario)
                .orElseThrow(() -> new OrdenNoEncontradaException(ordenId));
        if (marcada) {
            ComandoOrden comando = ComandoOrden.cancelar(orden.getId(), usuario.toString(), orden.getSimbolo(),
                    orden.getLado(), orden.getTipo(), orden.getPrecioLimite(), orden.getCantidad());
            // La misma clave que su NUEVA (el símbolo): la misma partición, después de ella
            outbox.guardar(Topicos.ORDERS_COMMANDS, comando.simbolo(), comando);
            return orden;
        }
        if (orden.getCancelacionSolicitadaEn() != null) {
            return orden;
        }
        if (orden.getEstado() == EstadoOrden.PENDIENTE) {
            throw OrdenNoCancelableException.pendiente();
        }
        throw OrdenNoCancelableException.terminada(orden.getEstado());
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
    private void validarUsuario(UUID usuario) {
        if (!usuarios.existsById(usuario)) {
            throw new UsuarioNoRegistradoException(usuario.toString());
        }
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
    private Instrumento validarInstrumento(String simbolo) {
        Instrumento instrumento = instrumentos.findById(simbolo)
                .orElseThrow(() -> InstrumentoNoDisponibleException.noExiste(simbolo));
        if (!instrumento.activo()) {
            throw InstrumentoNoDisponibleException.suspendido(simbolo);
        }
        return instrumento;
    }

    /**
     * RF-16: el precio de una orden LIMITE es múltiplo del tick y está a ±10 % del precio de referencia, el de la
     * última operación o, si todavía no hay ninguna, el precio inicial. Los límites cuentan: con referencia 5.000,
     * 4.500 y 5.500 pasan. Con enteros y sin redondeos: el mínimo es 90 % de la referencia redondeado hacia arriba
     * y el máximo, 110 % redondeado hacia abajo.
     *
     * @return el precio de referencia, que también va en la solicitud de reserva (una compra a mercado se reserva
     *         con él)
     */
    private static long validarPrecioLimite(CrearOrdenRequest request, Instrumento instrumento) {
        String simbolo = instrumento.getSimbolo();
        long referencia = instrumento.precioDeReferencia()
                .orElseThrow(() -> InstrumentoNoDisponibleException.sinPrecio(simbolo));
        if (request.tipo() == TipoOrden.MERCADO) {
            return referencia;
        }

        long precio = request.precioLimite();
        long tick = instrumento.getTick();
        if (precio % tick != 0) {
            long abajo = precio - precio % tick;
            throw new PrecioInvalidoException("El precio " + Pesos.formato(precio) + " no es múltiplo del tick de "
                    + simbolo + " (" + Pesos.formato(tick) + " COP): usa, por ejemplo, " + Pesos.formato(abajo)
                    + " o " + Pesos.formato(abajo + tick) + ".");
        }
        long minimo = (referencia * 9 + 9) / 10;
        long maximo = referencia * 11 / 10;
        if (precio < minimo || precio > maximo) {
            throw new PrecioInvalidoException("El precio " + Pesos.formato(precio) + " está a más de 10 % del último "
                    + "precio de " + simbolo + " (" + Pesos.formato(referencia) + " COP): debe estar entre "
                    + Pesos.formato(minimo) + " y " + Pesos.formato(maximo) + ".");
        }
        return referencia;
    }

    private static void validarPrecio(CrearOrdenRequest request) {
        if (request.tipo() == TipoOrden.LIMITE && request.precioLimite() == null) {
            throw new OrdenInvalidaException("Una orden LIMITE necesita precioLimite.");
        }
        if (request.tipo() == TipoOrden.MERCADO && request.precioLimite() != null) {
            throw new OrdenInvalidaException(
                    "Una orden MERCADO no lleva precioLimite: se ejecuta al mejor precio disponible.");
        }
    }
}
