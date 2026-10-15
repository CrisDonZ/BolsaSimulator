package co.bolsasim.auth.servicio;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class ConfiguracionSeguridad {

    /** BCrypt con factor 10: cada hash tarda ~0,1 s, caro para quien intente adivinar contraseñas (RNF-21). */
    @Bean
    public PasswordEncoder cifradorContrasenas() {
        return new BCryptPasswordEncoder(10);
    }
}
