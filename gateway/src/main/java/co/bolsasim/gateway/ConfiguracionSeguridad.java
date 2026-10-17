package co.bolsasim.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import reactor.core.publisher.Mono;

/**
 * Quién puede pasar por el gateway (RNF-22): todas las rutas exigen un JWT válido, salvo registrarse,
 * iniciar sesión, renovar el token y leer la clave pública.
 *
 * <p>El gateway solo valida; no cambia la petición. El servicio de destino recibe el mismo header
 * {@code Authorization: Bearer ...} y puede volver a validarlo con la misma clave pública.
 */
@Configuration
@EnableWebFluxSecurity
public class ConfiguracionSeguridad {

    /** Las únicas rutas que se usan sin haber iniciado sesión. Cualquier otra exige token. */
    static final ServerWebExchangeMatcher RUTAS_PUBLICAS = new OrServerWebExchangeMatcher(
            ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST,
                    "/api/usuarios", "/api/auth/login", "/api/auth/refresh"),
            ServerWebExchangeMatchers.pathMatchers(HttpMethod.GET,
                    "/.well-known/jwks.json", "/oauth2/jwks"));

    @Bean
    public SecurityWebFilterChain cadenaSeguridad(ServerHttpSecurity http, RespuestaNoAutorizado noAutorizado) {
        return http
                // Sin cookies ni sesiones: cada petición trae su token, así que no hay CSRF que proteger
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(rutas -> rutas
                        .matchers(RUTAS_PUBLICAS).permitAll()
                        .anyExchange().authenticated())
                .oauth2ResourceServer(servidor -> servidor
                        .bearerTokenConverter(ignorarTokenEnRutasPublicas())
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(noAutorizado))
                .exceptionHandling(errores -> errores.authenticationEntryPoint(noAutorizado))
                .build();
    }

    /**
     * Verifica la firma RS256 con la clave pública de auth-service, el vencimiento ("exp", con 60 s de
     * tolerancia por diferencias de reloj) y que el emisor sea auth-service ("iss").
     */
    @Bean
    public ReactiveJwtDecoder decodificadorJwt(@Value("${bolsasim.jwt.jwk-set-uri}") String jwkSetUri,
                                               @Value("${bolsasim.jwt.emisor}") String emisor) {
        NimbusReactiveJwtDecoder decodificador = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decodificador.setJwtValidator(JwtValidators.createDefaultWithIssuer(emisor));
        return decodificador;
    }

    /**
     * En las rutas públicas no se mira el token. Si el navegador manda un token vencido junto con
     * /api/auth/refresh (justo cuando más lo necesita), la petición pasa igual en vez de recibir 401.
     */
    private static ServerAuthenticationConverter ignorarTokenEnRutasPublicas() {
        ServerBearerTokenAuthenticationConverter lectorToken = new ServerBearerTokenAuthenticationConverter();
        return exchange -> RUTAS_PUBLICAS.matches(exchange)
                .flatMap(ruta -> ruta.isMatch() ? Mono.empty() : lectorToken.convert(exchange));
    }
}
