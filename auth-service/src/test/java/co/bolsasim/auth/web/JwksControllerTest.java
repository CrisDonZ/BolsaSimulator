package co.bolsasim.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.bolsasim.auth.seguridad.ConfiguracionSeguridad;
import co.bolsasim.auth.seguridad.RespuestaNoAutorizado;
import co.bolsasim.auth.servicio.ConfiguracionJwt;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Prueba del JWKS: levanta la capa web, la cadena de seguridad y la configuración real de las claves
 * (sin AUTH_JWT_CLAVE_PRIVADA, así que usa un par temporal). Las peticiones van sin token: es público.
 */
@WebMvcTest(JwksController.class)
@Import({ConfiguracionJwt.class, ConfiguracionSeguridad.class, RespuestaNoAutorizado.class})
class JwksControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RSAKey claveFirma;

    @ParameterizedTest
    @ValueSource(strings = {"/.well-known/jwks.json", "/oauth2/jwks"})
    void publicaLaClavePublicaDeFirmaConCacheControl(String ruta) throws Exception {
        mvc.perform(get(ruta))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=300, public"))
                .andExpect(jsonPath("$.keys.length()").value(1))
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].use").value("sig"))
                .andExpect(jsonPath("$.keys[0].kid").value(claveFirma.getKeyID()))
                .andExpect(jsonPath("$.keys[0].n").value(claveFirma.getModulus().toString()))
                .andExpect(jsonPath("$.keys[0].e").value(claveFirma.getPublicExponent().toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/.well-known/jwks.json", "/oauth2/jwks"})
    void nuncaPublicaLaParteSecretaDeLaClave(String ruta) throws Exception {
        String cuerpo = mvc.perform(get(ruta).accept(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString();

        RSAKey publicada = JWKSet.parse(cuerpo).getKeys().getFirst().toRSAKey();
        assertThat(publicada.isPrivate()).isFalse();
        assertThat(publicada.toRSAPublicKey()).isEqualTo(claveFirma.toRSAPublicKey());
        assertThat(cuerpo).doesNotContain("\"d\"", "\"p\"", "\"q\"", "\"dp\"", "\"dq\"", "\"qi\"");
    }
}
