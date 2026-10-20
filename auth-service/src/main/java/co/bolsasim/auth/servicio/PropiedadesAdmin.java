package co.bolsasim.auth.servicio;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Datos del primer administrador (sección {@code bolsasim.admin} de application.yml). Vienen de variables
 * de entorno, nunca del repositorio (RNF-26).
 *
 * @param correo     correo con el que inicia sesión; vacío no crea ningún administrador
 * @param contrasena contraseña; vacía no crea ningún administrador
 * @param alias      alias público del administrador
 */
@ConfigurationProperties("bolsasim.admin")
public record PropiedadesAdmin(String correo, String contrasena, String alias) {

    /** Sin la contraseña: así nunca termina en un log por accidente (RNF-21). */
    @Override
    public String toString() {
        return "PropiedadesAdmin[correo=" + correo + ", alias=" + alias + "]";
    }
}
