package co.bolsasim.auth.seguridad;

import co.bolsasim.auth.servicio.PropiedadesJwt;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * auth-service vuelve a validar el JWT aunque el gateway ya lo hizo (RNF-22): si alguien llega directo
 * al puerto 8082, sin pasar por el gateway, igual necesita un token firmado por auth-service para
 * consultar un perfil o cerrar sesión.
 *
 * <p>Registrarse, iniciar sesión, renovar el token y leer la clave pública no piden token.
 */
@Configuration
public class ConfiguracionSeguridad {

    /** Las únicas rutas que se usan sin haber iniciado sesión. Cualquier otra exige token. */
    static final RequestMatcher RUTAS_PUBLICAS = new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/usuarios"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/login"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/refresh"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/.well-known/jwks.json"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/oauth2/jwks"));

    @Bean
    public SecurityFilterChain cadenaSeguridad(HttpSecurity http, RespuestaNoAutorizado noAutorizado)
            throws Exception {
        return http
                // Sin cookies ni sesiones: cada petición trae su token, así que no hay CSRF que proteger
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(sesion -> sesion.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(rutas -> rutas
                        .requestMatchers(RUTAS_PUBLICAS).permitAll()
                        // La página de error de Spring: sin esto, un 400 del registro (JSON mal formado)
                        // llegaría como 401 a quien no tiene token
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(servidor -> servidor
                        .bearerTokenResolver(ignorarTokenEnRutasPublicas())
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(noAutorizado))
                .exceptionHandling(errores -> errores.authenticationEntryPoint(noAutorizado))
                .build();
    }

    /**
     * Las mismas reglas que el gateway y order-service: firma RS256, vencimiento ("exp", con 60 s de
     * tolerancia por diferencias de reloj) y emisor auth-service ("iss"). La diferencia es que aquí la
     * clave pública ya está en memoria: es la mitad pública de la clave con la que se firma.
     */
    @Bean
    public JwtDecoder decodificadorJwt(RSAKey claveFirma, PropiedadesJwt propiedades) throws JOSEException {
        NimbusJwtDecoder decodificador = NimbusJwtDecoder.withPublicKey(claveFirma.toRSAPublicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decodificador.setJwtValidator(JwtValidators.createDefaultWithIssuer(propiedades.emisor()));
        return decodificador;
    }

    /**
     * En las rutas públicas no se mira el token. Si el navegador manda un token vencido junto con
     * /api/auth/refresh (justo cuando más lo necesita), la petición pasa igual en vez de recibir 401.
     */
    private static BearerTokenResolver ignorarTokenEnRutasPublicas() {
        DefaultBearerTokenResolver lectorToken = new DefaultBearerTokenResolver();
        return peticion -> RUTAS_PUBLICAS.matches(peticion) ? null : lectorToken.resolve(peticion);
    }
}
