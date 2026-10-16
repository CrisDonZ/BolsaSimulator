package co.bolsasim.auth.servicio;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración de los tokens (sección {@code bolsasim.jwt} de application.yml).
 *
 * @param emisor           valor del campo "iss" de cada token
 * @param duracionAcceso   cuánto vale un token de acceso: 15 minutos (RF-02)
 * @param duracionRefresco cuánto vale un token de refresco: 7 días (RF-02)
 * @param clavePrivada     clave privada RSA en PEM; vacía genera un par temporal (solo desarrollo)
 */
@ConfigurationProperties("bolsasim.jwt")
public record PropiedadesJwt(String emisor, Duration duracionAcceso, Duration duracionRefresco,
                             String clavePrivada) {

    /** Sin la clave privada: así nunca termina en un log por accidente (RNF-26). */
    @Override
    public String toString() {
        return "PropiedadesJwt[emisor=" + emisor + ", duracionAcceso=" + duracionAcceso
                + ", duracionRefresco=" + duracionRefresco + "]";
    }
}
