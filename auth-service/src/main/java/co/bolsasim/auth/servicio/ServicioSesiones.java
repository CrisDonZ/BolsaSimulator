package co.bolsasim.auth.servicio;

import co.bolsasim.auth.sesion.RepositorioTokensRefresco;
import co.bolsasim.auth.sesion.TokenRefresco;
import co.bolsasim.auth.usuario.EstadoUsuario;
import co.bolsasim.auth.usuario.RepositorioUsuarios;
import co.bolsasim.auth.usuario.Usuario;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reglas de la sesión (HU-02): iniciar sesión con correo y contraseña, renovar el token de acceso
 * con el de refresco y cerrar sesión revocando el de refresco (RF-02, RF-03).
 */
@Service
public class ServicioSesiones {

    // BCrypt solo usa los primeros 72 bytes; una contraseña más larga no puede ser la de nadie
    private static final int MAXIMO_BYTES_BCRYPT = 72;

    private final RepositorioUsuarios usuarios;
    private final RepositorioTokensRefresco tokens;
    private final PasswordEncoder cifrador;
    private final EmisorTokens emisor;
    private final PropiedadesJwt propiedades;
    private final SecureRandom aleatorio = new SecureRandom();

    // Si el correo no existe, se compara contra este hash para que la respuesta tarde lo mismo
    // que con una contraseña equivocada: el tiempo no delata qué correos tienen cuenta
    private final String hashDeRelleno;

    public ServicioSesiones(RepositorioUsuarios usuarios, RepositorioTokensRefresco tokens, PasswordEncoder cifrador,
                            EmisorTokens emisor, PropiedadesJwt propiedades) {
        this.usuarios = usuarios;
        this.tokens = tokens;
        this.cifrador = cifrador;
        this.emisor = emisor;
        this.propiedades = propiedades;
        this.hashDeRelleno = cifrador.encode("contrasena-de-relleno");
    }

    @Transactional
    public Sesion iniciar(String correo, String contrasena) {
        if (contrasena.getBytes(StandardCharsets.UTF_8).length > MAXIMO_BYTES_BCRYPT) {
            throw new CredencialesInvalidasException();
        }
        Optional<Usuario> encontrado = usuarios.findByCorreo(correo.trim().toLowerCase(Locale.ROOT));
        boolean coincide = cifrador.matches(contrasena, encontrado.map(Usuario::getPasswordHash).orElse(hashDeRelleno));
        // Un usuario bloqueado recibe el mismo mensaje; el aviso de bloqueo llegará con HU-26
        if (encontrado.isEmpty() || !coincide || encontrado.get().getEstado() != EstadoUsuario.ACTIVO) {
            throw new CredencialesInvalidasException();
        }
        Usuario usuario = encontrado.get();

        String tokenRefresco = nuevoTokenRefresco();
        Instant refrescoExpiraEn = Instant.now().plus(propiedades.duracionRefresco());
        tokens.save(new TokenRefresco(usuario, hash(tokenRefresco), refrescoExpiraEn));
        return new Sesion(emisor.emitir(usuario), tokenRefresco, refrescoExpiraEn);
    }

    @Transactional(readOnly = true)
    public TokenAcceso renovar(String tokenRefresco) {
        TokenRefresco token = tokens.findByTokenHash(hash(tokenRefresco))
                .filter(t -> t.vigente(Instant.now()))
                .filter(t -> t.getUsuario().getEstado() == EstadoUsuario.ACTIVO)
                .orElseThrow(TokenRefrescoInvalidoException::new);
        return emisor.emitir(token.getUsuario());
    }

    /**
     * Si el token no existe o ya estaba revocado no pasa nada: el resultado es el mismo, sesión cerrada.
     * Si es de otro usuario tampoco pasa nada: nadie puede cerrar la sesión de otro (RNF-23).
     *
     * @param usuarioId el "sub" del token de acceso de quien cierra la sesión
     */
    @Transactional
    public void cerrar(String usuarioId, String tokenRefresco) {
        tokens.findByTokenHash(hash(tokenRefresco))
                .filter(token -> token.getUsuario().getId().toString().equals(usuarioId))
                .ifPresent(TokenRefresco::revocar);
    }

    /** 256 bits aleatorios: imposible de adivinar, por eso basta un SHA-256 para guardarlo (no hace falta BCrypt). */
    private String nuevoTokenRefresco() {
        byte[] bytes = new byte[32];
        aleatorio.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String tokenRefresco) {
        try {
            byte[] resumen = MessageDigest.getInstance("SHA-256").digest(tokenRefresco.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(resumen);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Toda JVM trae SHA-256", e);
        }
    }
}
