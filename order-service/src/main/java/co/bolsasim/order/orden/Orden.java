package co.bolsasim.order.orden;

import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.EstadoOrden;
import co.bolsasim.eventos.Lado;
import co.bolsasim.eventos.TipoOrden;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Tabla ORDEN del modelo entidad-relación: una orden de compra o venta de un usuario (RF-15, RF-20).
 *
 * <p>Su id es el mismo ordenId del comando que va al motor, así un evento del motor sobre esa orden
 * (orders.events) encuentra su fila. Implementa {@link Persistable} porque ese id lo pone order-service,
 * no la base: sin esto, guardar haría primero un SELECT para decidir entre INSERT y UPDATE.
 */
@Entity
public class Orden implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "usuario_id")
    private UUID usuarioId;

    private String simbolo;

    @Enumerated(EnumType.STRING)
    private Lado lado;

    @Enumerated(EnumType.STRING)
    private TipoOrden tipo;

    @Column(name = "precio_limite")
    private Long precioLimite;

    private int cantidad;

    @Column(name = "cantidad_ejecutada")
    private int cantidadEjecutada;

    @Column(name = "precio_promedio")
    private BigDecimal precioPromedio;

    @Enumerated(EnumType.STRING)
    private EstadoOrden estado;

    private String motivo;

    @Column(name = "clave_idempotencia")
    private String claveIdempotencia;

    @Column(name = "creada_en")
    private Instant creadaEn;

    @Column(name = "actualizada_en")
    private Instant actualizadaEn;

    /** Cuándo pidió el usuario cancelarla (HU-08); null si nunca lo pidió. Lo pone RepositorioOrdenes. */
    @Column(name = "cancelacion_solicitada_en")
    private Instant cancelacionSolicitadaEn;

    @Version
    private int version;

    @Transient
    private boolean nueva = true;

    protected Orden() {
        // Lo usa JPA al leer de la base
    }

    private Orden(ComandoOrden comando, UUID usuarioId, String claveIdempotencia, Instant ahora) {
        this.id = comando.ordenId();
        this.usuarioId = usuarioId;
        this.claveIdempotencia = claveIdempotencia;
        this.simbolo = comando.simbolo();
        this.lado = comando.lado();
        this.tipo = comando.tipo();
        this.precioLimite = comando.precioLimite();
        this.cantidad = comando.cantidad();
        this.cantidadEjecutada = 0;
        this.estado = EstadoOrden.PENDIENTE;
        this.creadaEn = ahora;
        this.actualizadaEn = ahora;
    }

    /**
     * La orden que corresponde a un comando NUEVA: empieza PENDIENTE y sin nada ejecutado.
     *
     * @param claveIdempotencia la del header Idempotency-Key: si la misma solicitud llega otra vez, se
     *                          devuelve esta orden en vez de crear otra (HU-10)
     * @param ahora             en microsegundos, como lo guarda PostgreSQL: es parte del cursor de la paginación
     */
    public static Orden nueva(ComandoOrden comando, UUID usuarioId, String claveIdempotencia, Instant ahora) {
        return new Orden(comando, usuarioId, claveIdempotencia, ahora);
    }

    /**
     * Aplica lo que el motor dice que pasó con la orden (orders.events). El evento trae el estado completo, no
     * solo lo que cambió.
     *
     * @param precioPromedio calculado desde todas las ejecuciones de la orden; null si no tiene
     * @throws EventoImposibleException si el evento no tiene sentido para esta orden: la orden ya terminó, la
     *                                  cantidad no coincide, lo ejecutado retrocede o supera lo pedido (regla 8)
     */
    public void aplicar(EstadoOrden nuevoEstado, int cantidadDelEvento, int nuevaCantidadEjecutada,
                        BigDecimal nuevoPrecioPromedio, String nuevoMotivo, Instant ahora) {
        if (estado.terminado()) {
            throw new EventoImposibleException("la orden ya terminó (" + estado + ")");
        }
        if (nuevoEstado == EstadoOrden.PENDIENTE || nuevoEstado == EstadoOrden.RECHAZADA) {
            throw new EventoImposibleException("el motor no pasa una orden a " + nuevoEstado);
        }
        if (cantidadDelEvento != cantidad) {
            throw new EventoImposibleException("el evento dice " + cantidadDelEvento + " acciones y la orden pide "
                    + cantidad);
        }
        if (nuevaCantidadEjecutada > cantidad) {
            throw new EventoImposibleException("se ejecutarían " + nuevaCantidadEjecutada + " de " + cantidad
                    + " acciones");
        }
        if (nuevaCantidadEjecutada < cantidadEjecutada) {
            throw new EventoImposibleException("lo ejecutado bajaría de " + cantidadEjecutada + " a "
                    + nuevaCantidadEjecutada);
        }
        if (nuevoEstado == EstadoOrden.EJECUTADA && nuevaCantidadEjecutada != cantidad) {
            throw new EventoImposibleException("EJECUTADA con " + nuevaCantidadEjecutada + " de " + cantidad
                    + " acciones");
        }
        this.estado = nuevoEstado;
        this.cantidadEjecutada = nuevaCantidadEjecutada;
        this.precioPromedio = nuevoPrecioPromedio;
        this.motivo = nuevoMotivo;
        this.actualizadaEn = ahora;
    }

    /**
     * El comando NUEVA que lleva la orden al motor, cuando portfolio-service aceptó su reserva.
     *
     * @throws EventoImposibleException si la orden ya no está PENDIENTE: ya tuvo respuesta de la reserva o del
     *                                  motor, y enviarla otra vez la duplicaría en el libro
     */
    public ComandoOrden comandoParaElMotor() {
        if (estado != EstadoOrden.PENDIENTE) {
            throw new EventoImposibleException("la reserva se aceptó, pero la orden ya está " + estado);
        }
        return ComandoOrden.paraOrden(id, usuarioId.toString(), simbolo, lado, tipo, precioLimite, cantidad);
    }

    /**
     * portfolio-service no pudo reservar el dinero o las acciones (RF-18): la orden termina sin llegar al motor.
     *
     * @throws EventoImposibleException si la orden ya no está PENDIENTE: el motor ya la tiene
     */
    public void rechazar(String nuevoMotivo, Instant ahora) {
        if (estado != EstadoOrden.PENDIENTE) {
            throw new EventoImposibleException("la reserva se rechazó, pero la orden ya está " + estado);
        }
        this.estado = EstadoOrden.RECHAZADA;
        this.motivo = nuevoMotivo;
        this.actualizadaEn = ahora;
    }

    /**
     * Si otra solicitud con la misma clave pide exactamente esta orden. El símbolo ya viene en mayúsculas:
     * "andx" y "ANDX" son la misma orden.
     */
    public boolean esLaMismaOrden(String simbolo, Lado lado, TipoOrden tipo, Long precioLimite, int cantidad) {
        return this.simbolo.equals(simbolo) && this.lado == lado && this.tipo == tipo
                && Objects.equals(this.precioLimite, precioLimite) && this.cantidad == cantidad;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return nueva;
    }

    @PostLoad
    @PostPersist
    void yaEstaEnLaBase() {
        nueva = false;
    }

    public UUID getUsuarioId() {
        return usuarioId;
    }

    public String getSimbolo() {
        return simbolo;
    }

    public Lado getLado() {
        return lado;
    }

    public TipoOrden getTipo() {
        return tipo;
    }

    public Long getPrecioLimite() {
        return precioLimite;
    }

    public int getCantidad() {
        return cantidad;
    }

    public int getCantidadEjecutada() {
        return cantidadEjecutada;
    }

    public BigDecimal getPrecioPromedio() {
        return precioPromedio;
    }

    public EstadoOrden getEstado() {
        return estado;
    }

    public String getMotivo() {
        return motivo;
    }

    public String getClaveIdempotencia() {
        return claveIdempotencia;
    }

    public Instant getCreadaEn() {
        return creadaEn;
    }

    public Instant getActualizadaEn() {
        return actualizadaEn;
    }

    public Instant getCancelacionSolicitadaEn() {
        return cancelacionSolicitadaEn;
    }
}
