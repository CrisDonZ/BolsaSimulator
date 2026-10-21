package co.bolsasim.order.instrumento;

import co.bolsasim.eventos.EstadoInstrumento;
import co.bolsasim.eventos.InstrumentoActualizado;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.Optional;

/**
 * Copia local de un instrumento de market-data-service: su estado, su tick y su precio de referencia. La llenan los
 * tópicos instruments.events y trades.executed; order-service la usa para rechazar órdenes de instrumentos que no
 * existen o están suspendidos y precios fuera del tick o de la banda (RF-16, RF-29).
 */
@Entity
public class Instrumento {

    @Id
    private String simbolo;

    @Enumerated(EnumType.STRING)
    private EstadoInstrumento estado;

    private long tick;

    @Column(name = "precio_inicial")
    private Long precioInicial;

    @Column(name = "ultimo_precio")
    private Long ultimoPrecio;

    @Column(name = "ultimo_precio_en")
    private Instant ultimoPrecioEn;

    @Column(name = "actualizado_en")
    private Instant actualizadoEn;

    protected Instrumento() {
        // Lo usa JPA al leer de la base
    }

    public Instrumento(InstrumentoActualizado evento) {
        this.simbolo = evento.simbolo();
        actualizar(evento);
    }

    /** Reemplaza la copia con el estado completo que trae el evento. El último precio no viene en él: se conserva. */
    public void actualizar(InstrumentoActualizado evento) {
        this.estado = evento.estado();
        this.tick = evento.tick();
        this.precioInicial = evento.precioInicial();
        this.actualizadoEn = evento.actualizadoEn();
    }

    public boolean activo() {
        return estado == EstadoInstrumento.ACTIVO;
    }

    /**
     * El precio de la última operación o, si todavía no hay ninguna, el precio inicial. Vacío solo en una copia de
     * antes de V7 que no ha recibido otro evento del instrumento.
     */
    public Optional<Long> precioDeReferencia() {
        return Optional.ofNullable(ultimoPrecio != null ? ultimoPrecio : precioInicial);
    }

    public String getSimbolo() {
        return simbolo;
    }

    public EstadoInstrumento getEstado() {
        return estado;
    }

    public long getTick() {
        return tick;
    }

    public Long getUltimoPrecio() {
        return ultimoPrecio;
    }

    public Instant getActualizadoEn() {
        return actualizadoEn;
    }
}
