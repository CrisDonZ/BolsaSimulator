package co.bolsasim.auth.servicio;

import co.bolsasim.auth.usuario.RepositorioUsuarios;
import co.bolsasim.auth.usuario.Rol;
import co.bolsasim.auth.usuario.Usuario;
import co.bolsasim.eventos.UsuarioRegistrado;
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
 * Crea al arrancar el bot creador de mercado (rol BOT), con el correo y la contraseña de las variables de entorno
 * BOLSASIM_CREADOR_MERCADO_CORREO y BOLSASIM_CREADOR_MERCADO_CONTRASENA. portfolio-service le acredita las acciones
 * emitidas de cada instrumento: es la oferta inicial del mercado (RF-59). Por la API nadie puede volverse BOT.
 *
 * <ul>
 *   <li>Sin las dos variables no crea a nadie: solo lo avisa en el log, y nadie recibe las acciones emitidas.</li>
 *   <li>Si ya existe una cuenta con ese correo que no es BOT, no la toca: si la ascendiera, quien se registrara
 *       primero con ese correo recibiría todas las acciones.</li>
 *   <li>Si la contraseña no cumple las reglas del registro, el servicio no arranca.</li>
 * </ul>
 *
 * <p>Publica users.registered con creadorDeMercado = true cada vez que arranca, no solo al crearlo: auth-service
 * no tiene outbox, y si el primer evento se perdió con Kafka caído, el bot nunca recibiría acciones. El evento
 * tiene siempre el mismo eventoId, así quien ya lo procesó lo ignora.
 */
@Component
@EnableConfigurationProperties(PropiedadesCreadorMercado.class)
public class CreadorDeMercado implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CreadorDeMercado.class);

    // Las mismas reglas que el registro; BCrypt solo usa los primeros 72 bytes
    private static final int MINIMO_CARACTERES = 8;
    private static final int MAXIMO_BYTES_BCRYPT = 72;
    private static final String ALIAS_POR_DEFECTO = "creador_mercado";

    private final RepositorioUsuarios repositorio;
    private final PasswordEncoder cifrador;
    private final PublicadorUsuarios publicador;
    private final PropiedadesCreadorMercado propiedades;

    public CreadorDeMercado(RepositorioUsuarios repositorio, PasswordEncoder cifrador, PublicadorUsuarios publicador,
                            PropiedadesCreadorMercado propiedades) {
        this.repositorio = repositorio;
        this.cifrador = cifrador;
        this.publicador = publicador;
        this.propiedades = propiedades;
    }

    @Override
    public void run(ApplicationArguments argumentos) {
        if (!StringUtils.hasText(propiedades.correo()) || !StringUtils.hasText(propiedades.contrasena())) {
            log.warn("No se creó el creador de mercado: define BOLSASIM_CREADOR_MERCADO_CORREO y "
                    + "BOLSASIM_CREADOR_MERCADO_CONTRASENA. Sin él, nadie recibe las acciones emitidas (RF-59).");
            return;
        }
        String correo = propiedades.correo().trim().toLowerCase(Locale.ROOT);
        if (!correo.matches("[^@\\s]+@[^@\\s]+")) {
            throw new IllegalStateException("BOLSASIM_CREADOR_MERCADO_CORREO no es un correo válido.");
        }
        validarContrasena(propiedades.contrasena());

        var existente = repositorio.findByCorreo(correo);
        if (existente.isPresent()) {
            Usuario cuenta = existente.get();
            if (cuenta.getRol() != Rol.BOT) {
                log.warn("El correo de BOLSASIM_CREADOR_MERCADO_CORREO ya es de una cuenta que no es BOT: no se "
                        + "asciende. Usa otro correo para el creador de mercado.");
                return;
            }
            publicar(cuenta);
            return;
        }
        String alias = StringUtils.hasText(propiedades.alias()) ? propiedades.alias().trim() : ALIAS_POR_DEFECTO;
        if (!alias.matches("[A-Za-z0-9_]{3,30}")) {
            throw new IllegalStateException(
                    "BOLSASIM_CREADOR_MERCADO_ALIAS debe tener de 3 a 30 letras, números o _.");
        }
        if (repositorio.existsByAliasIgnoreCase(alias)) {
            throw new IllegalStateException("El alias " + alias + " ya está en uso: define otro en "
                    + "BOLSASIM_CREADOR_MERCADO_ALIAS.");
        }

        Usuario bot = repositorio.saveAndFlush(Usuario.nuevoBot(
                "Creador de mercado", alias, correo, cifrador.encode(propiedades.contrasena()), Instant.now()));
        log.info("Creador de mercado {} creado con el id {}", alias, bot.getId());
        publicar(bot);
    }

    private void publicar(Usuario bot) {
        publicador.publicar(UsuarioRegistrado.delCreadorDeMercado(bot.getId(), bot.getAlias(), bot.getCreadoEn()));
    }

    private static void validarContrasena(String contrasena) {
        if (contrasena.length() < MINIMO_CARACTERES
                || contrasena.getBytes(StandardCharsets.UTF_8).length > MAXIMO_BYTES_BCRYPT) {
            throw new IllegalStateException("BOLSASIM_CREADOR_MERCADO_CONTRASENA debe tener entre 8 caracteres y "
                    + "72 bytes, como cualquier contraseña de BolsaSim.");
        }
    }
}
