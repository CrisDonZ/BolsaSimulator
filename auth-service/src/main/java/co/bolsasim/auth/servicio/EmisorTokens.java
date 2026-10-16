package co.bolsasim.auth.servicio;

import co.bolsasim.auth.usuario.Usuario;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Emite los tokens de acceso: JWT firmados con RS256 que vencen a los 15 minutos (RF-02).
 *
 * <p>Llevan solo lo que el gateway necesita para autorizar (RNF-30): quién es el usuario ("sub")
 * y su rol. Nada de correo ni nombre, porque cualquiera que tenga el token puede leerlo.
 */
@Component
public class EmisorTokens {

    private final JwtEncoder codificador;
    private final PropiedadesJwt propiedades;

    public EmisorTokens(JwtEncoder codificador, PropiedadesJwt propiedades) {
        this.codificador = codificador;
        this.propiedades = propiedades;
    }

    public TokenAcceso emitir(Usuario usuario) {
        // El JWT guarda las fechas en segundos: así accesoExpiraEn de la respuesta coincide con el "exp" del token
        Instant ahora = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        JwtClaimsSet datos = JwtClaimsSet.builder()
                .issuer(propiedades.emisor())
                .subject(usuario.getId().toString())
                .claim("rol", usuario.getRol().name())
                .issuedAt(ahora)
                .expiresAt(ahora.plus(propiedades.duracionAcceso()))
                .id(UUID.randomUUID().toString())
                .build();
        JwsHeader cabecera = JwsHeader.with(SignatureAlgorithm.RS256).build();
        Jwt jwt = codificador.encode(JwtEncoderParameters.from(cabecera, datos));
        return new TokenAcceso(jwt.getTokenValue(), jwt.getExpiresAt());
    }
}
