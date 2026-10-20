package co.bolsasim.eventos;

import java.time.Instant;

/**
 * Evento que market-data-service publica en {@link Topicos#INSTRUMENTS_EVENTS} cada vez que un instrumento
 * se crea, se edita, se suspende o se reactiva.
 *
 * <p>Lleva el estado completo del instrumento, no solo lo que cambió: el tópico es compactado y Kafka
 * conserva solo el último mensaje de cada símbolo, así que ese mensaje debe bastar por sí solo.
 *
 * @param simbolo          código de la acción, en mayúsculas (ej. ANDX); es la clave del mensaje
 * @param nombre           empresa ficticia
 * @param sector           sector económico
 * @param precioInicial    precio de salida en COP
 * @param tick             variación mínima de precio en COP
 * @param accionesEmitidas total de acciones; portfolio-service se las asignará al bot creador de mercado (RF-59)
 * @param estado           ACTIVO o SUSPENDIDO
 * @param actualizadoEn    momento del cambio; un evento más viejo que la copia local se ignora
 */
public record InstrumentoActualizado(
        String simbolo,
        String nombre,
        String sector,
        long precioInicial,
        long tick,
        long accionesEmitidas,
        EstadoInstrumento estado,
        Instant actualizadoEn) {
}
