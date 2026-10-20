package co.bolsasim.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Tokens para las pruebas de las rutas protegidas, firmados como los de auth-service (RS256, con sub,
 * rol, iss, iat, exp y jti). Se firman con la clave que se le pase: la de auth-service para un token
 * legítimo u otra para uno falsificado.
 */
public final class TokensDePrueba {

    public static final String EMISOR = "bolsasim-auth";

    private TokensDePrueba() {
    }

    /** Un token vigente de 15 minutos. */
    public static String valido(RSAKey clave, String usuarioId, String rol) {
        return firmar(clave, EMISOR, usuarioId, rol, Instant.now(), Instant.now().plus(Duration.ofMinutes(15)));
    }

    /** Emitido hace 20 minutos: venció hace 5, más que la tolerancia de 60 s. */
    public static String vencido(RSAKey clave, String usuarioId) {
        Instant hace20Minutos = Instant.now().minus(Duration.ofMinutes(20));
        return firmar(clave, EMISOR, usuarioId, "INVERSIONISTA", hace20Minutos, hace20Minutos.plus(Duration.ofMinutes(15)));
    }

    /** El mismo token con el "sub" cambiado por otro usuario y la firma original: ya no coincide. */
    public static String alterado(String token, String usuarioId, String otroUsuarioId) {
        String[] partes = token.split("\\.");
        String datos = new String(Base64.getUrlDecoder().decode(partes[1]), StandardCharsets.UTF_8)
                .replace(usuarioId, otroUsuarioId);
        return partes[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(datos.getBytes(StandardCharsets.UTF_8)) + "." + partes[2];
    }

    /** Dice ser de la clave de auth-service ("kid"), pero lo firmó otra: así se vería un token falsificado. */
    public static String firmadoConOtraClave(RSAKey claveReal, String usuarioId) {
        RSAKey falsa = new RSAKey.Builder(generarClave()).keyID(claveReal.getKeyID()).build();
        return valido(falsa, usuarioId, "INVERSIONISTA");
    }

    public static String firmar(RSAKey clave, String emisor, String usuarioId, String rol, Instant emitido,
                                Instant vence) {
        JWTClaimsSet datos = new JWTClaimsSet.Builder()
                .issuer(emisor)
                .subject(usuarioId)
                .claim("rol", rol)
                .issueTime(Date.from(emitido))
                .expirationTime(Date.from(vence))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(clave.getKeyID()).build(), datos);
        try {
            jwt.sign(new RSASSASigner(clave));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    private static RSAKey generarClave() {
        try {
            return new RSAKeyGenerator(2048).keyIDFromThumbprint(true).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
