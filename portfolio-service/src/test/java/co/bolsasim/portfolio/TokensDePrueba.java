package co.bolsasim.portfolio;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Hace de auth-service en las pruebas: firma tokens como los suyos (RS256, con sub, rol, iss, iat, exp
 * y jti) con una clave generada para la prueba y publica la clave pública en un JWKS, con el servidor
 * HTTP que trae el JDK. portfolio-service la descarga de ahí igual que de auth-service.
 */
public final class TokensDePrueba {

    public static final RSAKey CLAVE = generarClave();
    public static final String EMISOR = "bolsasim-auth";

    private static final String JWKS_URI = publicarJwks();

    private TokensDePrueba() {
    }

    /** Apunta portfolio-service al JWKS de prueba. Se usa desde un {@code @DynamicPropertySource}. */
    public static void configurar(DynamicPropertyRegistry propiedades) {
        propiedades.add("bolsasim.jwt.jwk-set-uri", () -> JWKS_URI);
    }

    /** Un token vigente de 15 minutos de un usuario nuevo con ese rol (INVERSIONISTA, ADMIN o BOT). */
    public static String valido(String rol) {
        return firmar(CLAVE, EMISOR, rol, Instant.now(), Instant.now().plus(Duration.ofMinutes(15)));
    }

    /** Un token vigente de 15 minutos de ese usuario, con ese rol. */
    public static String de(UUID usuarioId, String rol) {
        return firmar(CLAVE, EMISOR, usuarioId.toString(), rol, Instant.now(), Instant.now().plus(Duration.ofMinutes(15)));
    }

    public static String firmar(RSAKey clave, String emisor, String rol, Instant emitido, Instant vence) {
        return firmar(clave, emisor, UUID.randomUUID().toString(), rol, emitido, vence);
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

    public static RSAKey generarClave() {
        try {
            return new RSAKeyGenerator(2048).keyIDFromThumbprint(true).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String publicarJwks() {
        byte[] jwks = new JWKSet(CLAVE.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        HttpServer servidor;
        try {
            servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        servidor.createContext("/.well-known/jwks.json", intercambio -> {
            intercambio.getResponseHeaders().set("Content-Type", "application/json");
            intercambio.sendResponseHeaders(200, jwks.length);
            intercambio.getResponseBody().write(jwks);
            intercambio.close();
        });
        servidor.start();
        return "http://127.0.0.1:" + servidor.getAddress().getPort() + "/.well-known/jwks.json";
    }
}
