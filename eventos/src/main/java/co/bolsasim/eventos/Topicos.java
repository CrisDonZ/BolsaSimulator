package co.bolsasim.eventos;

/**
 * Nombres de los tópicos de Kafka. Tenerlos en un solo lugar evita errores de tipeo
 * entre el servicio que publica y el que consume.
 */
public final class Topicos {

    /** Comandos NUEVA y CANCELAR que van al motor. Clave del mensaje = símbolo. */
    public static final String ORDERS_COMMANDS = "orders.commands";

    private Topicos() {
    }
}
