package co.bolsasim.auth.servicio;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.StringUtils;

/**
 * Par de claves RSA con el que auth-service firma los tokens de acceso (RS256).
 *
 * <p>Solo auth-service tiene la clave privada, así que solo él puede emitir tokens. El gateway y los
 * demás servicios verifican la firma con la clave pública, que se publica en /.well-known/jwks.json.
 */
@Configuration
@EnableConfigurationProperties(PropiedadesJwt.class)
public class ConfiguracionJwt {

    private static final Logger log = LoggerFactory.getLogger(ConfiguracionJwt.class);

    // Una clave RSA de menos de 2048 bits ya no se considera segura (NIST SP 800-57)
    private static final int MINIMO_BITS = 2048;

    @Bean
    public RSAKey claveFirma(PropiedadesJwt propiedades) {
        if (StringUtils.hasText(propiedades.clavePrivada())) {
            return desdePem(propiedades.clavePrivada());
        }
        log.warn("AUTH_JWT_CLAVE_PRIVADA no está definida: se generó un par de claves temporal. "
                + "Sirve para desarrollo, pero al reiniciar auth-service los tokens emitidos dejan de valer.");
        return generar();
    }

    @Bean
    public JwtEncoder codificadorJwt(RSAKey claveFirma) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(claveFirma)));
    }

    /** Lee una clave privada RSA en PEM (PKCS#8) y calcula su clave pública. Rechaza claves de menos de 2048 bits. */
    static RSAKey desdePem(String pem) {
        // Acepta el PEM con saltos de línea reales o escritos como \n (así suelen llegar en variables de entorno)
        String base64 = pem.replace("\\n", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            KeyFactory fabrica = KeyFactory.getInstance("RSA");
            var privada = (RSAPrivateCrtKey) fabrica.generatePrivate(
                    new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
            if (privada.getModulus().bitLength() < MINIMO_BITS) {
                throw new IllegalStateException("AUTH_JWT_CLAVE_PRIVADA es una clave RSA de "
                        + privada.getModulus().bitLength() + " bits; el mínimo es " + MINIMO_BITS);
            }
            var publica = (RSAPublicKey) fabrica.generatePublic(
                    new RSAPublicKeySpec(privada.getModulus(), privada.getPublicExponent()));
            return construir(publica, privada);
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw new IllegalStateException(
                    "AUTH_JWT_CLAVE_PRIVADA no es una clave privada RSA en formato PEM (PKCS#8)", e);
        }
    }

    static RSAKey generar() {
        try {
            KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
            generador.initialize(MINIMO_BITS);
            var par = generador.generateKeyPair();
            return construir((RSAPublicKey) par.getPublic(), (RSAPrivateCrtKey) par.getPrivate());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo generar el par de claves RSA", e);
        }
    }

    /** El "kid" es la huella de la clave pública: el gateway sabrá con qué clave verificar cada token. */
    private static RSAKey construir(RSAPublicKey publica, RSAPrivateCrtKey privada) {
        try {
            return new RSAKey.Builder(publica)
                    .privateKey(privada)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyIDFromThumbprint()
                    .build();
        } catch (JOSEException e) {
            throw new IllegalStateException("No se pudo calcular el identificador de la clave", e);
        }
    }
}
