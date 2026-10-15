package co.bolsasim.eventos;

/**
 * Qué le pide el comando al motor. NUEVA y CANCELAR viajan por el mismo tópico
 * para que una cancelación nunca llegue antes que su orden.
 */
public enum AccionComando {
    NUEVA,
    CANCELAR
}
