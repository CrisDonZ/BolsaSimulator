package co.bolsasim.order.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.EstadoInstrumento;
import co.bolsasim.eventos.InstrumentoActualizado;
import co.bolsasim.order.TokensDePrueba;
import co.bolsasim.order.instrumento.Instrumento;
import co.bolsasim.order.instrumento.RepositorioInstrumentos;
import co.bolsasim.order.seguridad.ConfiguracionSeguridad;
import co.bolsasim.order.seguridad.RespuestaNoAutorizado;
import co.bolsasim.order.servicio.PublicadorOrdenes;
import co.bolsasim.order.servicio.ServicioOrdenes;
import co.bolsasim.order.usuario.RepositorioUsuarios;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Prueba de la capa web con la seguridad real: los tokens se firman con la clave de prueba y
 * order-service los valida contra el JWKS de {@link TokensDePrueba}, como haría con auth-service.
 * El servicio de órdenes también es el real; el publicador de Kafka y las copias locales de usuarios
 * e instrumentos son dobles de Mockito, así se puede ver qué comando se publicaría sin levantar Kafka ni base de datos.
 */
@WebMvcTest(OrdenController.class)
@Import({ConfiguracionSeguridad.class, RespuestaNoAutorizado.class, ServicioOrdenes.class})
class OrdenControllerTest {

    private static final String USUARIO = UUID.randomUUID().toString();
    private static final String ORDEN = """
            {"simbolo":"ANDX","lado":"COMPRA","tipo":"LIMITE","precioLimite":5000,"cantidad":100}""";

    @DynamicPropertySource
    static void jwksDePrueba(DynamicPropertyRegistry propiedades) {
        TokensDePrueba.configurar(propiedades);
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PublicadorOrdenes publicador;

    @MockitoBean
    private RepositorioUsuarios usuarios;

    @MockitoBean
    private RepositorioInstrumentos instrumentos;

    @BeforeEach
    void elUsuarioDelTokenEstaRegistradoYAndxEstaActivo() {
        when(usuarios.existsById(UUID.fromString(USUARIO))).thenReturn(true);
        when(instrumentos.findById("ANDX")).thenReturn(Optional.of(instrumento("ANDX", EstadoInstrumento.ACTIVO)));
    }

    @Test
    void unaOrdenDeUnInstrumentoSuspendidoResponde422YNoSePublica() throws Exception {
        when(instrumentos.findById("ANDX")).thenReturn(Optional.of(instrumento("ANDX", EstadoInstrumento.SUSPENDIDO)));

        enviar(ORDEN, TokensDePrueba.valido(USUARIO))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Instrumento no disponible"))
                .andExpect(jsonPath("$.detail").value("El instrumento ANDX está suspendido: no recibe órdenes nuevas."));
        verifyNoInteractions(publicador);
    }

    @Test
    void unaOrdenDeUnInstrumentoQueNoExisteResponde422YNoSePublica() throws Exception {
        enviar(ORDEN.replace("ANDX", "NADA"), TokensDePrueba.valido(USUARIO))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Instrumento no disponible"))
                .andExpect(jsonPath("$.detail").value("El instrumento NADA no existe."));
        verifyNoInteractions(publicador);
    }

    @Test
    void conUnTokenValidoLaOrdenSePublicaANombreDelSubDelToken() throws Exception {
        enviar(ORDEN, TokensDePrueba.valido(USUARIO))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.simbolo").value("ANDX"))
                .andExpect(jsonPath("$.ordenId").isNotEmpty());

        ArgumentCaptor<ComandoOrden> publicado = ArgumentCaptor.forClass(ComandoOrden.class);
        verify(publicador).publicar(publicado.capture());
        assertThat(publicado.getValue().usuarioId()).isEqualTo(USUARIO);
        assertThat(publicado.getValue().simbolo()).isEqualTo("ANDX");
    }

    @Test
    void sinTokenResponde401YNoSePublica() throws Exception {
        enviar(ORDEN, null)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)))
                .andExpect(jsonPath("$.detail").value(startsWith("Falta el token de acceso")));
        verifyNoInteractions(publicador);
    }

    @Test
    void conUnTokenVencidoResponde401() throws Exception {
        Instant hace20Minutos = Instant.now().minus(Duration.ofMinutes(20));
        String vencido = TokensDePrueba.firmar(TokensDePrueba.CLAVE, TokensDePrueba.EMISOR, USUARIO,
                hace20Minutos, hace20Minutos.plus(Duration.ofMinutes(15)));

        rechazado(enviar(ORDEN, vencido));
    }

    @Test
    void conLaFirmaAlteradaResponde401() throws Exception {
        // Se cambia el "sub" por el de otro usuario y se deja la firma original: ya no coincide
        String[] partes = TokensDePrueba.valido(USUARIO).split("\\.");
        String otroUsuario = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(partes[1]), StandardCharsets.UTF_8)
                        .replace(USUARIO, UUID.randomUUID().toString())
                        .getBytes(StandardCharsets.UTF_8));

        rechazado(enviar(ORDEN, partes[0] + "." + otroUsuario + "." + partes[2]));
    }

    @Test
    void conUnTokenFirmadoConOtraClaveResponde401() throws Exception {
        // Dice ser de la clave de auth-service ("kid"), pero lo firmó otra: así se vería un token falsificado
        RSAKey falsa = new RSAKey.Builder(TokensDePrueba.generarClave()).keyID(TokensDePrueba.CLAVE.getKeyID()).build();
        String falsificado = TokensDePrueba.firmar(falsa, TokensDePrueba.EMISOR, USUARIO,
                Instant.now(), Instant.now().plus(Duration.ofMinutes(15)));

        rechazado(enviar(ORDEN, falsificado));
    }

    @Test
    void conUnTokenDeOtroEmisorResponde401() throws Exception {
        String deOtroEmisor = TokensDePrueba.firmar(TokensDePrueba.CLAVE, "otro-sistema", USUARIO,
                Instant.now(), Instant.now().plus(Duration.ofMinutes(15)));

        rechazado(enviar(ORDEN, deOtroEmisor));
    }

    @Test
    void siElCuerpoTraeUsuarioIdResponde400YNoSePublica() throws Exception {
        // Aunque el token sea válido, nadie puede poner una orden a nombre de otro usuario
        String aNombreDeOtro = """
                {"usuarioId":"%s","simbolo":"ANDX","lado":"COMPRA","tipo":"LIMITE","precioLimite":5000,"cantidad":100}"""
                .formatted(UUID.randomUUID());

        enviar(aNombreDeOtro, TokensDePrueba.valido(USUARIO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "usuarioId ya no se envía: el usuario de la orden sale del token de acceso."));
        verifyNoInteractions(publicador);
    }

    @Test
    void unCampoQueNoExisteResponde400SinRepetirloEnLaRespuesta() throws Exception {
        String conCampoDeMas = """
                {"simbolo":"ANDX","lado":"COMPRA","tipo":"LIMITE","precioLimite":5000,"cantidad":100,"comision":0}""";

        enviar(conCampoDeMas, TokensDePrueba.valido(USUARIO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(startsWith("La orden trae un campo que no existe")));
        verifyNoInteractions(publicador);
    }

    @Test
    void unLadoQueNoExisteResponde400() throws Exception {
        enviar(ORDEN.replace("COMPRA", "REGALO"), TokensDePrueba.valido(USUARIO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("COMPRA o VENTA")));
        verifyNoInteractions(publicador);
    }

    @Test
    void unaCantidadInvalidaResponde400ConElCampo() throws Exception {
        enviar(ORDEN.replace("\"cantidad\":100", "\"cantidad\":0"), TokensDePrueba.valido(USUARIO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("cantidad")));
        verifyNoInteractions(publicador);
    }

    @Test
    void unTokenValidoDeUnUsuarioQueAunNoEstaEnLaCopiaLocalResponde422() throws Exception {
        // Inició sesión antes de que users.registered llegara a order-service
        String recienRegistrado = UUID.randomUUID().toString();

        enviar(ORDEN, TokensDePrueba.valido(recienRegistrado))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Usuario no registrado"))
                .andExpect(jsonPath("$.detail").value(containsString(recienRegistrado + " no está registrado")));
        verifyNoInteractions(publicador);
    }

    private ResultActions enviar(String cuerpo, String token) throws Exception {
        var peticion = post("/api/ordenes").contentType(MediaType.APPLICATION_JSON).content(cuerpo);
        if (token != null) {
            peticion.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(peticion);
    }

    private static Instrumento instrumento(String simbolo, EstadoInstrumento estado) {
        return new Instrumento(new InstrumentoActualizado(simbolo, "Andex Energía", "Energía", 5_000, 10, 1_000_000,
                estado, Instant.now()));
    }

    private void rechazado(ResultActions respuesta) throws Exception {
        respuesta.andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""))
                .andExpect(jsonPath("$.detail").value(startsWith("El token de acceso no es válido o ya venció")));
        verifyNoInteractions(publicador);
    }
}
