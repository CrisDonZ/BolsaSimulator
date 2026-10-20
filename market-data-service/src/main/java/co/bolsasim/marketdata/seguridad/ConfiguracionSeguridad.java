package co.bolsasim.marketdata.seguridad;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * market-data-service valida el JWT por su cuenta aunque el gateway ya lo hizo (RNF-22): quien llegue
 * directo al puerto 8083 igual necesita un token firmado por auth-service.
 *
 * <p>Consultar el catálogo pide solo el token. Crear, editar, suspender y reactivar piden además el rol
 * ADMIN, que sale del claim "rol" del token (RF-54). El gateway no mira roles: lo decide cada servicio.
 */
@Configuration
public class ConfiguracionSeguridad {

    private static final String ADMIN = "ADMIN";

    @Bean
    public SecurityFilterChain cadenaSeguridad(HttpSecurity http, RespuestaNoAutorizado noAutorizado,
                                               RespuestaProhibido prohibido) throws Exception {
        return http
                // Sin cookies ni sesiones: cada petición trae su token, así que no hay CSRF que proteger
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(sesion -> sesion.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(rutas -> rutas
                        .requestMatchers(HttpMethod.GET, "/api/instrumentos", "/api/instrumentos/*").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/instrumentos", "/api/instrumentos/*/suspender",
                                "/api/instrumentos/*/reactivar").hasRole(ADMIN)
                        .requestMatchers(HttpMethod.PUT, "/api/instrumentos/*").hasRole(ADMIN)
                        // La página de error de Spring (un 400 o un 404) no debe volverse 401
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // Una ruta que no está en la lista también pide token: nada queda abierto por olvido
                        .anyRequest().authenticated())
                .oauth2ResourceServer(servidor -> servidor
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(rolDelToken()))
                        .authenticationEntryPoint(noAutorizado)
                        .accessDeniedHandler(prohibido))
                .exceptionHandling(errores -> errores
                        .authenticationEntryPoint(noAutorizado)
                        .accessDeniedHandler(prohibido))
                .build();
    }

    /**
     * Las mismas reglas que el gateway: firma RS256 con la clave pública de auth-service, vencimiento
     * ("exp", con 60 s de tolerancia por diferencias de reloj) y emisor auth-service ("iss").
     */
    @Bean
    public JwtDecoder decodificadorJwt(@Value("${bolsasim.jwt.jwk-set-uri}") String jwkSetUri,
                                       @Value("${bolsasim.jwt.emisor}") String emisor) {
        NimbusJwtDecoder decodificador = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decodificador.setJwtValidator(JwtValidators.createDefaultWithIssuer(emisor));
        return decodificador;
    }

    /** El claim "rol" (INVERSIONISTA, ADMIN o BOT) se vuelve el permiso ROLE_ADMIN, ROLE_BOT... de Spring. */
    private static JwtAuthenticationConverter rolDelToken() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("rol");
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter convertidor = new JwtAuthenticationConverter();
        convertidor.setJwtGrantedAuthoritiesConverter(roles);
        return convertidor;
    }
}
