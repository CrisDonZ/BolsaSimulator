package co.bolsasim.eventos;

import java.time.Instant;
import java.util.UUID;

/**
 * Evento que auth-service publica en {@link Topicos#USERS_REGISTERED} cuando un usuario se registra.
 * Lo consumirá portfolio-service para crear la cuenta con el saldo inicial (RF-07).
 *
 * <p>No lleva nombre ni correo: los otros servicios no los necesitan, y así los datos
 * personales se quedan en auth-service (RNF-30, Ley 1581).
 *
 * @param eventoId     identificador único del evento (sirve para detectar duplicados)
 * @param usuarioId    id del usuario en auth-service; es el usuarioId de sus órdenes
 * @param alias        nombre público con el que aparece en el ranking
 * @param rol          INVERSIONISTA, ADMIN o BOT (los bots no aparecen en el ranking)
 * @param registradoEn momento del registro
 */
public record UsuarioRegistrado(
        UUID eventoId,
        UUID usuarioId,
        String alias,
        String rol,
        Instant registradoEn) {

    /** Crea el evento con un eventoId nuevo. */
    public static UsuarioRegistrado de(UUID usuarioId, String alias, String rol, Instant registradoEn) {
        return new UsuarioRegistrado(UUID.randomUUID(), usuarioId, alias, rol, registradoEn);
    }
}
