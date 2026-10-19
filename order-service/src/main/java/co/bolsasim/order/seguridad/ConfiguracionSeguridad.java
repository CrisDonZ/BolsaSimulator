package co.bolsasim.order.seguridad;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * order-service vuelve a validar el JWT aunque el gateway ya lo hizo (RNF-22): si alguien llega
 * directo al puerto 8081, sin pasar por el gateway, igual necesita un token firmado por auth-service.
 * Así nadie puede fabricarse un token con el "sub" de otro usuario y operar a su nombre (RNF-23).
 *
 * <p>No hay rutas públicas: todo lo de order-service es del usuario que inició sesión.
 */
@Configuration
public class ConfiguracionSeguridad {

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
                .authorizeHttpRequests(rutas -> rutas.anyRequest().authenticated())
                .oauth2ResourceServer(servidor -> servidor
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(noAutorizado))
                .exceptionHandling(errores -> errores.authenticationEntryPoint(noAutorizado))
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
}
