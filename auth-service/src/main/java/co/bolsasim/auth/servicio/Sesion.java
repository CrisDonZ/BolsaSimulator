package co.bolsasim.auth.servicio;

import java.time.Instant;

/** Lo que recibe el usuario al iniciar sesión: el token de acceso y el de refresco con su vencimiento. */
public record Sesion(TokenAcceso acceso, String tokenRefresco, Instant refrescoExpiraEn) {
}
