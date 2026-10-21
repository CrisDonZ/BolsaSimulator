package co.bolsasim.eventos;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Mensaje que order-service publica en {@link Topicos#ORDERS_COMMANDS} y que lee el motor.
 *
 * <p>Es un {@code record}: inmutable, con constructor, getters, equals y hashCode generados.
 * Un evento describe algo que ya se decidió, por eso no se modifica después de creado.
 *
 * @param comandoId    identificador único del mensaje (sirve para detectar duplicados)
 * @param accion       NUEVA o CANCELAR
 * @param ordenId      orden a la que se refiere el comando
 * @param usuarioId    quién envía la orden
 * @param simbolo      acción que se negocia, en mayúsculas (ej. ANDX)
 * @param lado         COMPRA o VENTA
 * @param tipo         MERCADO o LIMITE
 * @param precioLimite precio en COP; null si la orden es a MERCADO
 * @param cantidad     número de acciones, mayor que cero
 * @param enviadoEn    momento en que se publicó
 */
public record ComandoOrden(
        UUID comandoId,
        AccionComando accion,
        UUID ordenId,
        String usuarioId,
        String simbolo,
        Lado lado,
        TipoOrden tipo,
        Long precioLimite,
        int cantidad,
        Instant enviadoEn) {

    /** Crea el comando para una orden nueva, con identificadores y fecha generados. */
    public static ComandoOrden nueva(String usuarioId, String simbolo, Lado lado,
                                     TipoOrden tipo, Long precioLimite, int cantidad) {
        return new ComandoOrden(
                UUID.randomUUID(),
                AccionComando.NUEVA,
                UUID.randomUUID(),
                usuarioId,
                simbolo.toUpperCase(Locale.ROOT),
                lado,
                tipo,
                precioLimite,
                cantidad,
                Instant.now());
    }

    /**
     * El comando NUEVA de una orden que ya existe, cuando su reserva se aceptó. El comandoId sale del ordenId:
     * si la aceptación se procesara dos veces, el motor recibiría el mismo comando y lo reconocería como
     * repetido, en vez de meter la orden dos veces en el libro.
     */
    public static ComandoOrden paraOrden(UUID ordenId, String usuarioId, String simbolo, Lado lado, TipoOrden tipo,
                                         Long precioLimite, int cantidad) {
        UUID comandoId = UUID.nameUUIDFromBytes((ordenId + "/NUEVA").getBytes(StandardCharsets.UTF_8));
        return new ComandoOrden(comandoId, AccionComando.NUEVA, ordenId, usuarioId, simbolo, lado, tipo, precioLimite,
                cantidad, Instant.now());
    }
}
