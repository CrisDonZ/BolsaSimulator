package co.bolsasim.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Prueba del gateway completo (RNF-22): arranca en un puerto real y reenvía a {@link ServiciosFalsos},
 * que hacen de auth-service y order-service y publican la clave pública de prueba.
 * Los tokens se firman aquí con una clave RSA generada para la prueba, igual que lo hace auth-service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewaySeguridadTest {

    private static final RSAKey CLAVE = generarClave();
    private static final ServiciosFalsos SERVICIOS =
            new ServiciosFalsos(new JWKSet(CLAVE.toPublicJWK()).toString());
    private static final String USUARIO = UUID.randomUUID().toString();
    private static final String ORDEN = """
            {"usuarioId":"%s","simbolo":"ANDX","lado":"COMPRA","tipo":"LIMITE","precioLimite":5000,"cantidad":100}
            """.formatted(USUARIO);

    @DynamicPropertySource
    static void serviciosFalsos(DynamicPropertyRegistry propiedades) {
        propiedades.add("bolsasim.servicios.auth", SERVICIOS::url);
        propiedades.add("bolsasim.servicios.ordenes", SERVICIOS::url);
    }

    @Value("${local.server.port}")
    private int puerto;

    private WebTestClient cliente;

    @BeforeEach
    void preparar() {
        cliente = WebTestClient.bindToServer().baseUrl("http://localhost:" + puerto).build();
        SERVICIOS.olvidar();
    }

    @Test
    void unaOrdenSinTokenResponde401YNoLlegaAOrderService() {
        enviarOrden(null)
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.detail").value(detalle ->
                        assertThat((String) detalle).startsWith("Falta el token de acceso"));
        assertThat(SERVICIOS.recibidas("/api/ordenes")).isEmpty();
    }

    @Test
    void unTokenVencidoResponde401() {
        Instant hace20Minutos = Instant.now().minus(Duration.ofMinutes(20));
        String vencido = firmar(CLAVE, "bolsasim-auth", hace20Minutos, hace20Minutos.plus(Duration.ofMinutes(15)));

        rechazado(enviarOrden(vencido));
    }

    @Test
    void unTokenConElContenidoAlteradoResponde401() {
        // Se cambia el "sub" por el de otro usuario y se deja la firma original: ya no coincide
        String[] partes = valido().split("\\.");
        String otroUsuario = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(partes[1]), StandardCharsets.UTF_8)
                        .replace(USUARIO, UUID.randomUUID().toString())
                        .getBytes(StandardCharsets.UTF_8));

        rechazado(enviarOrden(partes[0] + "." + otroUsuario + "." + partes[2]));
    }

    @Test
    void unTokenFirmadoConOtraClaveResponde401() {
        // Dice ser de la clave de auth-service ("kid"), pero lo firmó otra: así se vería un token falsificado
        RSAKey falsa = new RSAKey.Builder(generarClave()).keyID(CLAVE.getKeyID()).build();
        String falsificado = firmar(falsa, "bolsasim-auth", Instant.now(), Instant.now().plus(Duration.ofMinutes(15)));

        rechazado(enviarOrden(falsificado));
    }

    @Test
    void unTokenDeOtroEmisorResponde401() {
        String deOtroEmisor = firmar(CLAVE, "otro-sistema", Instant.now(), Instant.now().plus(Duration.ofMinutes(15)));

        rechazado(enviarOrden(deOtroEmisor));
    }

    @Test
    void conUnTokenValidoLaOrdenLlegaAOrderServiceConElMismoAuthorization() {
        String token = valido();

        enviarOrden(token).expectStatus().isOk();

        assertThat(SERVICIOS.recibidas("/api/ordenes"))
                .singleElement()
                .satisfies(peticion -> {
                    assertThat(peticion.metodo()).isEqualTo("POST");
                    assertThat(peticion.authorization()).isEqualTo("Bearer " + token);
                });
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /api/usuarios",
            "POST, /api/auth/login",
            "POST, /api/auth/refresh",
            "GET,  /.well-known/jwks.json",
            "GET,  /oauth2/jwks"})
    void lasRutasPublicasPasanSinToken(String metodo, String ruta) {
        cliente.method(HttpMethod.valueOf(metodo)).uri(ruta)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isOk();

        assertThat(SERVICIOS.recibidas(ruta)).hasSize(1);
    }

    @Test
    void renovarConUnTokenVencidoEnElHeaderIgualPasa() {
        // El navegador puede mandar el token vencido justo cuando pide uno nuevo: no debe recibir 401
        Instant hace20Minutos = Instant.now().minus(Duration.ofMinutes(20));
        String vencido = firmar(CLAVE, "bolsasim-auth", hace20Minutos, hace20Minutos.plus(Duration.ofMinutes(15)));

        cliente.post().uri("/api/auth/refresh")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + vencido)
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"tokenRefresco\":\"abc\"}")
                .exchange()
                .expectStatus().isOk();
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /api/auth/logout",
            "GET,  /api/usuarios/123e4567-e89b-12d3-a456-426614174000",
            "GET,  /api/ordenes"})
    void lasDemasRutasDeLosServiciosExigenToken(String metodo, String ruta) {
        cliente.method(HttpMethod.valueOf(metodo)).uri(ruta)
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(SERVICIOS.recibidas(ruta)).isEmpty();
    }

    @Test
    void unaRutaQueNoExisteTambienExigeToken() {
        cliente.get().uri("/actuator/env").exchange().expectStatus().isUnauthorized();

        cliente.get().uri("/actuator/env")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + valido())
                .exchange()
                .expectStatus().isNotFound();
    }

    private WebTestClient.ResponseSpec enviarOrden(String token) {
        WebTestClient.RequestBodySpec peticion = cliente.post().uri("/api/ordenes");
        if (token != null) {
            peticion.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return peticion.contentType(MediaType.APPLICATION_JSON).bodyValue(ORDEN).exchange();
    }

    private static void rechazado(WebTestClient.ResponseSpec respuesta) {
        respuesta.expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
                .expectBody().jsonPath("$.detail").value(detalle ->
                        assertThat((String) detalle).startsWith("El token de acceso no es válido o ya venció"));
        assertThat(SERVICIOS.recibidas("/api/ordenes")).isEmpty();
    }

    private static String valido() {
        return firmar(CLAVE, "bolsasim-auth", Instant.now(), Instant.now().plus(Duration.ofMinutes(15)));
    }

    /** Un token como los de auth-service: RS256, con sub, rol, iss, iat, exp y jti. */
    private static String firmar(RSAKey clave, String emisor, Instant emitido, Instant vence) {
        JWTClaimsSet datos = new JWTClaimsSet.Builder()
                .issuer(emisor)
                .subject(USUARIO)
                .claim("rol", "INVERSIONISTA")
                .issueTime(Date.from(emitido))
                .expirationTime(Date.from(vence))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(clave.getKeyID()).build(), datos);
        try {
            jwt.sign(new RSASSASigner(clave));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    private static RSAKey generarClave() {
        try {
            return new RSAKeyGenerator(2048).keyIDFromThumbprint(true).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
