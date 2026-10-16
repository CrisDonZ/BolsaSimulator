package co.bolsasim.auth.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Prueba unitaria de cómo se obtiene el par de claves: de AUTH_JWT_CLAVE_PRIVADA o generado al arrancar. */
class ConfiguracionJwtTest {

    @Test
    void conLaClavePrivadaEnPemSeUsaEsaClaveYSeCalculaSuClavePublica() throws Exception {
        KeyPair par = parRsa();

        RSAKey clave = configuracion(pem(par, "\n"));

        assertThat(clave.toRSAPublicKey().getModulus()).isEqualTo(((RSAPublicKey) par.getPublic()).getModulus());
        assertThat(clave.isPrivate()).isTrue();
        assertThat(clave.getKeyID()).isNotBlank();
    }

    @Test
    void elPemTambienSeAceptaConLosSaltosDeLineaEscritosComoBarraN() throws Exception {
        KeyPair par = parRsa();

        RSAKey clave = configuracion(pem(par, "\\n"));

        assertThat(clave.toRSAPublicKey().getModulus()).isEqualTo(((RSAPublicKey) par.getPublic()).getModulus());
    }

    @Test
    void sinClavePrivadaSeGeneraUnParTemporal() {
        RSAKey clave = configuracion("");

        assertThat(clave.isPrivate()).isTrue();
        assertThat(clave.size()).isEqualTo(2048);
    }

    @Test
    void unaClaveMalEscritaDetieneElArranqueConUnMensajeClaro() {
        assertThatThrownBy(() -> configuracion("-----BEGIN PRIVATE KEY-----\nesto no es una clave\n"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_JWT_CLAVE_PRIVADA");
    }

    @Test
    void elJwksPublicadoNoIncluyeLaParteSecretaDeLaClave() throws JOSEException {
        RSAKey publica = configuracion("").toPublicJWK();

        assertThat(publica.isPrivate()).isFalse();
        assertThat(publica.toJSONObject()).doesNotContainKeys("d", "p", "q");
    }

    private static RSAKey configuracion(String clavePrivada) {
        return new ConfiguracionJwt().claveFirma(
                new PropiedadesJwt("bolsasim-auth", Duration.ofMinutes(15), Duration.ofDays(7), clavePrivada));
    }

    private static KeyPair parRsa() throws NoSuchAlgorithmException {
        KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
        generador.initialize(2048);
        return generador.generateKeyPair();
    }

    /** La clave privada como la escribe openssl: PKCS#8 en Base64, en líneas de 64 caracteres. */
    private static String pem(KeyPair par, String saltoDeLinea) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(par.getPrivate().getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----";
        return pem.replace("\n", saltoDeLinea);
    }
}
