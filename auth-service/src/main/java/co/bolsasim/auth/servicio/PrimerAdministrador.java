package co.bolsasim.auth.servicio;

import co.bolsasim.auth.usuario.RepositorioUsuarios;
import co.bolsasim.auth.usuario.Rol;
import co.bolsasim.auth.usuario.Usuario;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Crea el primer ADMIN al arrancar, con el correo y la contraseña de las variables de entorno
 * BOLSASIM_ADMIN_CORREO y BOLSASIM_ADMIN_CONTRASENA. Por la API nadie puede volverse ADMIN.
 *
 * <ul>
 *   <li>Sin las dos variables no crea a nadie: solo lo avisa en el log.</li>
 *   <li>Si ya existe una cuenta con ese correo no la toca, y nunca la asciende a ADMIN: si lo hiciera,
 *       quien se registrara primero con ese correo, con su propia contraseña, sería administrador.</li>
 *   <li>Si la contraseña no cumple las reglas del registro, el servicio no arranca.</li>
 * </ul>
 *
 * <p>No publica users.registered: el administrador no opera, así que no necesita cuenta de dinero.
 */
@Component
@EnableConfigurationProperties(PropiedadesAdmin.class)
public class PrimerAdministrador implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PrimerAdministrador.class);

    // Las mismas reglas que el registro; BCrypt solo usa los primeros 72 bytes
    private static final int MINIMO_CARACTERES = 8;
    private static final int MAXIMO_BYTES_BCRYPT = 72;
    private static final String ALIAS_POR_DEFECTO = "admin";

    private final RepositorioUsuarios repositorio;
    private final PasswordEncoder cifrador;
    private final PropiedadesAdmin propiedades;

    public PrimerAdministrador(RepositorioUsuarios repositorio, PasswordEncoder cifrador, PropiedadesAdmin propiedades) {
        this.repositorio = repositorio;
        this.cifrador = cifrador;
        this.propiedades = propiedades;
    }

    @Override
    public void run(ApplicationArguments argumentos) {
        if (!StringUtils.hasText(propiedades.correo()) || !StringUtils.hasText(propiedades.contrasena())) {
            log.warn("No se creó ningún administrador: define BOLSASIM_ADMIN_CORREO y BOLSASIM_ADMIN_CONTRASENA "
                    + "para crear el primero.");
            return;
        }
        String correo = propiedades.correo().trim().toLowerCase(Locale.ROOT);
        if (!correo.matches("[^@\\s]+@[^@\\s]+")) {
            throw new IllegalStateException("BOLSASIM_ADMIN_CORREO no es un correo válido.");
        }
        validarContrasena(propiedades.contrasena());

        var existente = repositorio.findByCorreo(correo);
        if (existente.isPresent()) {
            if (existente.get().getRol() != Rol.ADMIN) {
                log.warn("El correo de BOLSASIM_ADMIN_CORREO ya es de una cuenta que no es ADMIN: no se asciende. "
                        + "Usa otro correo para el administrador.");
            }
            return;
        }
        String alias = StringUtils.hasText(propiedades.alias()) ? propiedades.alias().trim() : ALIAS_POR_DEFECTO;
        if (!alias.matches("[A-Za-z0-9_]{3,30}")) {
            throw new IllegalStateException("BOLSASIM_ADMIN_ALIAS debe tener de 3 a 30 letras, números o _.");
        }
        if (repositorio.existsByAliasIgnoreCase(alias)) {
            throw new IllegalStateException("El alias " + alias + " ya está en uso: define otro en BOLSASIM_ADMIN_ALIAS.");
        }

        Usuario admin = repositorio.saveAndFlush(Usuario.nuevoAdmin(
                "Administrador", alias, correo, cifrador.encode(propiedades.contrasena()), Instant.now()));
        log.info("Administrador {} creado con el id {}", alias, admin.getId());
    }

    private static void validarContrasena(String contrasena) {
        if (contrasena.length() < MINIMO_CARACTERES
                || contrasena.getBytes(StandardCharsets.UTF_8).length > MAXIMO_BYTES_BCRYPT) {
            throw new IllegalStateException("BOLSASIM_ADMIN_CONTRASENA debe tener entre 8 caracteres y 72 bytes, "
                    + "como cualquier contraseña de BolsaSim.");
        }
    }
}
