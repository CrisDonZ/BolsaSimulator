package co.bolsasim.eventos;

/**
 * Nombres de los tópicos de Kafka. Tenerlos en un solo lugar evita errores de tipeo
 * entre el servicio que publica y el que consume.
 */
public final class Topicos {

    /** Comandos NUEVA y CANCELAR que van al motor. Clave del mensaje = símbolo. */
    public static final String ORDERS_COMMANDS = "orders.commands";

    /** Cambios de estado de las órdenes que publica el motor. Clave del mensaje = símbolo. */
    public static final String ORDERS_EVENTS = "orders.events";

    /** Operaciones (una compra y una venta que se cruzaron) que publica el motor. Clave del mensaje = símbolo. */
    public static final String TRADES_EXECUTED = "trades.executed";

    /** Usuarios recién registrados en auth-service. Clave del mensaje = id del usuario. */
    public static final String USERS_REGISTERED = "users.registered";

    /** Alta, cambio, suspensión o reactivación de un instrumento. Compactado; clave del mensaje = símbolo. */
    public static final String INSTRUMENTS_EVENTS = "instruments.events";

    private Topicos() {
    }
}
