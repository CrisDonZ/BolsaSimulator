package co.bolsasim.order.orden;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Dónde quedó la página anterior: la fecha y el id de su última orden. La siguiente página empieza justo
 * después, en el orden de la lista (creada_en DESC, id DESC).
 *
 * <p>Viaja en base64 para que el cliente lo trate como un valor opaco. No hace falta firmarlo: el usuario
 * siempre sale del token, así que un cursor alterado solo cambia el punto de partida dentro de sus propias
 * órdenes.
 */
public record Cursor(Instant creadaEn, UUID id) {

    public static Cursor despuesDe(Orden orden) {
        return new Cursor(orden.getCreadaEn(), orden.getId());
    }

    public String codificar() {
        String texto = creadaEn + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(texto.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws IllegalArgumentException si no es un cursor que haya generado order-service */
    public static Cursor decodificar(String codificado) {
        try {
            String[] partes = new String(Base64.getUrlDecoder().decode(codificado), StandardCharsets.UTF_8).split("\\|");
            if (partes.length != 2) {
                throw new IllegalArgumentException("El cursor no tiene dos partes");
            }
            return new Cursor(Instant.parse(partes[0]), UUID.fromString(partes[1]));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Cursor inválido", e);
        }
    }
}
