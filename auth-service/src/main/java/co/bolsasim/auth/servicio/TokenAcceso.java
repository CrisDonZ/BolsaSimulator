package co.bolsasim.auth.servicio;

import java.time.Instant;

/** Un JWT firmado y el momento en que vence. */
public record TokenAcceso(String valor, Instant expiraEn) {
}
