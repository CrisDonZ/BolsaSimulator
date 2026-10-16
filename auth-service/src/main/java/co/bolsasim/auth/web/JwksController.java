package co.bolsasim.auth.web;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publica la clave pública con la que se verifican los tokens (formato JWKS, RFC 7517).
 * El gateway la leerá de aquí para validar la firma de cada token (RNF-22).
 * La clave privada nunca sale: {@code toPublicJWK()} solo deja la parte pública.
 */
@RestController
public class JwksController {

    private final Map<String, Object> jwks;

    public JwksController(RSAKey claveFirma) {
        this.jwks = new JWKSet(claveFirma.toPublicJWK()).toJSONObject();
    }

    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        return jwks;
    }
}
