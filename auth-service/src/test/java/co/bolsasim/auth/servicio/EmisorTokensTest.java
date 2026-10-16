package co.bolsasim.auth.servicio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.bolsasim.auth.usuario.Usuario;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Prueba unitaria del token de acceso: se verifica como lo hará el gateway, solo con la clave pública.
 */
class EmisorTokensTest {

    private static final PropiedadesJwt PROPIEDADES =
            new PropiedadesJwt("bolsasim-auth", Duration.ofMinutes(15), Duration.ofDays(7), null);

    private final RSAKey clave = ConfiguracionJwt.generar();
    private final EmisorTokens emisor =
            new EmisorTokens(new ConfiguracionJwt().codificadorJwt(clave), PROPIEDADES);

    @Test
    void elTokenSeVerificaConLaClavePublicaYDiceQuienEsElUsuarioYSuRol() throws JOSEException {
        Usuario ana = usuario();

        TokenAcceso token = emisor.emitir(ana);
        Jwt jwt = NimbusJwtDecoder.withPublicKey(clave.toRSAPublicKey()).build().decode(token.valor());

        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256").containsEntry("kid", clave.getKeyID());
        assertThat(jwt.getSubject()).isEqualTo(ana.getId().toString());
        assertThat(jwt.getClaimAsString("rol")).isEqualTo("INVERSIONISTA");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("bolsasim-auth");
        assertThat(jwt.getId()).isNotBlank();
    }

    @Test
    void elTokenDeAccesoVenceALos15Minutos() throws JOSEException {
        TokenAcceso token = emisor.emitir(usuario());
        Jwt jwt = NimbusJwtDecoder.withPublicKey(clave.toRSAPublicKey()).build().decode(token.valor());

        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
        assertThat(token.expiraEn()).isEqualTo(jwt.getExpiresAt());
    }

    @Test
    void elTokenNoLlevaDatosPersonales() throws JOSEException {
        TokenAcceso token = emisor.emitir(usuario());
        Jwt jwt = NimbusJwtDecoder.withPublicKey(clave.toRSAPublicKey()).build().decode(token.valor());

        assertThat(jwt.getClaims()).containsOnlyKeys("iss", "sub", "rol", "iat", "exp", "jti");
    }

    @Test
    void unTokenFirmadoConOtraClaveSeRechaza() throws JOSEException {
        TokenAcceso token = emisor.emitir(usuario());
        RSAKey otraClave = ConfiguracionJwt.generar();

        assertThatThrownBy(() -> NimbusJwtDecoder.withPublicKey(otraClave.toRSAPublicKey()).build()
                .decode(token.valor()))
                .isInstanceOf(JwtException.class);
    }

    private static Usuario usuario() {
        Usuario usuario = Usuario.nuevoInversionista("Ana Pérez", "ana_inv", "ana@correo.com", "hash", Instant.now());
        ReflectionTestUtils.setField(usuario, "id", UUID.randomUUID());
        return usuario;
    }
}
