package co.bolsasim.auth.servicio;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Datos del bot creador de mercado (sección {@code bolsasim.creador-mercado} de application.yml). Vienen de
 * variables de entorno, nunca del repositorio (RNF-26).
 *
 * @param correo     correo con el que inicia sesión; vacío no crea la cuenta
 * @param contrasena contraseña; vacía no crea la cuenta
 * @param alias      alias público del bot
 */
@ConfigurationProperties("bolsasim.creador-mercado")
public record PropiedadesCreadorMercado(String correo, String contrasena, String alias) {

    /** Sin la contraseña: así nunca termina en un log por accidente (RNF-21). */
    @Override
    public String toString() {
        return "PropiedadesCreadorMercado[correo=" + correo + ", alias=" + alias + "]";
    }
}
