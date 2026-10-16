package co.bolsasim.auth.web;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publica la clave pública con la que se verifican los tokens (formato JWKS, RFC 7517).
 * El gateway la leerá de aquí para validar la firma de cada token (RNF-22).
 * La clave privada nunca sale: {@code toPublicJWK()} solo deja la parte pública.
 *
 * <p>Responde en dos rutas: /.well-known/jwks.json es la convención más común y /oauth2/jwks es la que
 * usa Spring Authorization Server, así sirve a clientes configurados con cualquiera de las dos.
 */
@RestController
public class JwksController {

    // Los clientes pueden guardar la clave unos minutos en vez de pedirla en cada petición. Es corto
    // porque en desarrollo la clave cambia al reiniciar auth-service; y si llega un token con un "kid"
    // que no conocen, los clientes de Spring vuelven a pedir el JWKS sin esperar a que venza.
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    private final Map<String, Object> jwks;

    public JwksController(RSAKey claveFirma) {
        this.jwks = new JWKSet(claveFirma.toPublicJWK()).toJSONObject();
    }

    @GetMapping({"/.well-known/jwks.json", "/oauth2/jwks"})
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok().cacheControl(CACHE).body(jwks);
    }
}
