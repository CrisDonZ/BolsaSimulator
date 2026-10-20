package co.bolsasim.eventos;

/**
 * ACTIVO: se negocia normalmente.
 * SUSPENDIDO: rechaza órdenes nuevas, pero permite cancelar y conserva su libro (RF-29).
 */
public enum EstadoInstrumento {
    ACTIVO,
    SUSPENDIDO
}
