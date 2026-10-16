package co.bolsasim.eventos;

/**
 * Nombres de los tópicos de Kafka. Tenerlos en un solo lugar evita errores de tipeo
 * entre el servicio que publica y el que consume.
 */
public final class Topicos {

    /** Comandos NUEVA y CANCELAR que van al motor. Clave del mensaje = símbolo. */
    public static final String ORDERS_COMMANDS = "orders.commands";

    /** Usuarios recién registrados en auth-service. Clave del mensaje = id del usuario. */
    public static final String USERS_REGISTERED = "users.registered";

    private Topicos() {
    }
}
