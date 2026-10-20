package co.bolsasim.matching.motor;

/**
 * Un mensaje listo para Kafka: tópico, clave y evento.
 *
 * @param evento un EventoOrden u OperacionEjecutada
 */
public record Mensaje(String topico, String clave, Object evento) {
}
