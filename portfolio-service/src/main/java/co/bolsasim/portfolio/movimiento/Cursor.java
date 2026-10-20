package co.bolsasim.portfolio.movimiento;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Dónde quedó la página anterior: la fecha y el id de su último movimiento. La siguiente página empieza justo
 * después, en el orden de la lista (creado_en DESC, id DESC). El mismo formato que el cursor de las órdenes.
 *
 * <p>Viaja en base64 para que el cliente lo trate como un valor opaco. No hace falta firmarlo: la cuenta siempre
 * sale del token, así que un cursor alterado solo cambia el punto de partida dentro de sus propios movimientos.
 */
public record Cursor(Instant creadoEn, UUID id) {

    public static Cursor despuesDe(Movimiento movimiento) {
        return new Cursor(movimiento.creadoEn(), movimiento.id());
    }

    public String codificar() {
        String texto = creadoEn + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(texto.getBytes(StandardCharsets.UTF_8));
    }

    /** @throws IllegalArgumentException si no es un cursor que haya generado portfolio-service */
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
