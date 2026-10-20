package co.bolsasim.marketdata.instrumento;

import co.bolsasim.eventos.EstadoInstrumento;
import co.bolsasim.eventos.InstrumentoActualizado;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import java.time.Instant;
import org.springframework.data.domain.Persistable;

/**
 * Tabla INSTRUMENTO del modelo entidad-relación: una empresa ficticia que se negocia (RF-54).
 *
 * <p>Una vez creado solo cambian el nombre, el sector y el estado. El símbolo es la llave y la clave
 * de partición en Kafka; las acciones emitidas son constantes (RNF-08); el precio inicial solo sirve de
 * punto de partida; y un tick nuevo dejaría en precios inválidos las órdenes que ya están en el libro.
 *
 * <p>Implementa {@link Persistable} porque el símbolo lo pone quien crea el instrumento, no la base:
 * sin esto, guardar un símbolo que ya existe lo sobrescribiría en vez de fallar.
 */
@Entity
public class Instrumento implements Persistable<String> {

    @Id
    private String simbolo;

    private String nombre;

    private String sector;

    @Column(name = "precio_inicial")
    private long precioInicial;

    private long tick;

    @Column(name = "acciones_emitidas")
    private long accionesEmitidas;

    @Enumerated(EnumType.STRING)
    private EstadoInstrumento estado;

    @Column(name = "creado_en")
    private Instant creadoEn;

    @Column(name = "actualizado_en")
    private Instant actualizadoEn;

    @Transient
    private boolean nuevo = true;

    protected Instrumento() {
        // Lo usa JPA al leer de la base
    }

    public Instrumento(String simbolo, String nombre, String sector, long precioInicial, long tick,
                       long accionesEmitidas, Instant ahora) {
        this.simbolo = simbolo;
        this.nombre = nombre;
        this.sector = sector;
        this.precioInicial = precioInicial;
        this.tick = tick;
        this.accionesEmitidas = accionesEmitidas;
        this.estado = EstadoInstrumento.ACTIVO;
        this.creadoEn = ahora;
        this.actualizadoEn = ahora;
    }

    /** @return true si algo cambió (y hay que avisar a los demás servicios) */
    public boolean editar(String nombre, String sector, Instant ahora) {
        if (this.nombre.equals(nombre) && this.sector.equals(sector)) {
            return false;
        }
        this.nombre = nombre;
        this.sector = sector;
        this.actualizadoEn = ahora;
        return true;
    }

    /** @return true si estaba activo; suspender uno ya suspendido no cambia nada */
    public boolean suspender(Instant ahora) {
        return cambiarEstado(EstadoInstrumento.SUSPENDIDO, ahora);
    }

    /** @return true si estaba suspendido */
    public boolean reactivar(Instant ahora) {
        return cambiarEstado(EstadoInstrumento.ACTIVO, ahora);
    }

    private boolean cambiarEstado(EstadoInstrumento nuevoEstado, Instant ahora) {
        if (estado == nuevoEstado) {
            return false;
        }
        estado = nuevoEstado;
        actualizadoEn = ahora;
        return true;
    }

    /** El estado completo, como lo necesita el tópico compactado instruments.events. */
    public InstrumentoActualizado comoEvento() {
        return new InstrumentoActualizado(simbolo, nombre, sector, precioInicial, tick, accionesEmitidas, estado,
                actualizadoEn);
    }

    @Override
    public String getId() {
        return simbolo;
    }

    @Override
    public boolean isNew() {
        return nuevo;
    }

    @PostLoad
    @PostPersist
    void yaEstaEnLaBase() {
        nuevo = false;
    }

    public String getSimbolo() {
        return simbolo;
    }

    public String getNombre() {
        return nombre;
    }

    public String getSector() {
        return sector;
    }

    public long getPrecioInicial() {
        return precioInicial;
    }

    public long getTick() {
        return tick;
    }

    public long getAccionesEmitidas() {
        return accionesEmitidas;
    }

    public EstadoInstrumento getEstado() {
        return estado;
    }

    public Instant getCreadoEn() {
        return creadoEn;
    }

    public Instant getActualizadoEn() {
        return actualizadoEn;
    }
}
