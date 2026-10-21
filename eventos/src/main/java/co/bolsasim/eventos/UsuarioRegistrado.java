package co.bolsasim.eventos;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Evento que auth-service publica en {@link Topicos#USERS_REGISTERED} cuando un usuario se registra.
 * Lo consumirá portfolio-service para crear la cuenta con el saldo inicial (RF-07).
 *
 * <p>No lleva nombre ni correo: los otros servicios no los necesitan, y así los datos
 * personales se quedan en auth-service (RNF-30, Ley 1581).
 *
 * @param eventoId         identificador único del evento (sirve para detectar duplicados)
 * @param usuarioId        id del usuario en auth-service; es el usuarioId de sus órdenes
 * @param alias            nombre público con el que aparece en el ranking
 * @param rol              INVERSIONISTA, ADMIN o BOT (los bots no aparecen en el ranking)
 * @param registradoEn     momento del registro
 * @param creadorDeMercado true solo para el bot creador de mercado, que recibe las acciones emitidas de cada
 *                         instrumento (RF-59). Los eventos de antes de este campo no lo traen y se leen como false
 */
public record UsuarioRegistrado(
        UUID eventoId,
        UUID usuarioId,
        String alias,
        String rol,
        Instant registradoEn,
        boolean creadorDeMercado) {

    /** Crea el evento de un usuario que no es el creador de mercado, con un eventoId nuevo. */
    public static UsuarioRegistrado de(UUID usuarioId, String alias, String rol, Instant registradoEn) {
        return new UsuarioRegistrado(UUID.randomUUID(), usuarioId, alias, rol, registradoEn, false);
    }

    /**
     * El evento del creador de mercado (rol BOT). auth-service lo publica cada vez que arranca, por si el primero se
     * perdió con Kafka caído: el eventoId sale del usuarioId, así quien ya lo procesó lo reconoce como repetido.
     */
    public static UsuarioRegistrado delCreadorDeMercado(UUID usuarioId, String alias, Instant registradoEn) {
        UUID eventoId = UUID.nameUUIDFromBytes((usuarioId + "/creador-de-mercado").getBytes(StandardCharsets.UTF_8));
        return new UsuarioRegistrado(eventoId, usuarioId, alias, "BOT", registradoEn, true);
    }
}
