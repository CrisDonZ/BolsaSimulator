package co.bolsasim.order.instrumento;

import co.bolsasim.eventos.EstadoInstrumento;
import co.bolsasim.eventos.InstrumentoActualizado;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import java.time.Instant;

/**
 * Copia local de un instrumento de market-data-service: su estado y su tick. La llena el tópico
 * instruments.events; order-service la usa para rechazar órdenes de instrumentos que no existen o
 * están suspendidos (RF-16, RF-29).
 */
@Entity
public class Instrumento {

    @Id
    private String simbolo;

    @Enumerated(EnumType.STRING)
    private EstadoInstrumento estado;

    private long tick;

    @Column(name = "actualizado_en")
    private Instant actualizadoEn;

    protected Instrumento() {
        // Lo usa JPA al leer de la base
    }

    public Instrumento(InstrumentoActualizado evento) {
        this.simbolo = evento.simbolo();
        actualizar(evento);
    }

    /** Reemplaza la copia con el estado completo que trae el evento. */
    public void actualizar(InstrumentoActualizado evento) {
        this.estado = evento.estado();
        this.tick = evento.tick();
        this.actualizadoEn = evento.actualizadoEn();
    }

    public boolean activo() {
        return estado == EstadoInstrumento.ACTIVO;
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

    public Instant getActualizadoEn() {
        return actualizadoEn;
    }
}
