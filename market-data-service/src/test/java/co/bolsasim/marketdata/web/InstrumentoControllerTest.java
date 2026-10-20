package co.bolsasim.marketdata.web;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.bolsasim.marketdata.TokensDePrueba;
import co.bolsasim.marketdata.instrumento.Instrumento;
import co.bolsasim.marketdata.seguridad.ConfiguracionSeguridad;
import co.bolsasim.marketdata.seguridad.RespuestaNoAutorizado;
import co.bolsasim.marketdata.seguridad.RespuestaProhibido;
import co.bolsasim.marketdata.servicio.InstrumentoDuplicadoException;
import co.bolsasim.marketdata.servicio.InstrumentoInvalidoException;
import co.bolsasim.marketdata.servicio.InstrumentoNoEncontradoException;
import co.bolsasim.marketdata.servicio.ServicioInstrumentos;
import com.nimbusds.jose.jwk.RSAKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Prueba de la capa web con la seguridad real: los tokens se firman con la clave de prueba y
 * market-data-service los valida contra el JWKS de {@link TokensDePrueba}, como haría con auth-service.
 * El servicio es un doble de Mockito: aquí importa quién puede hacer qué y qué responde cada error.
 */
@WebMvcTest(InstrumentoController.class)
@Import({ConfiguracionSeguridad.class, RespuestaNoAutorizado.class, RespuestaProhibido.class})
class InstrumentoControllerTest {

    private static final String NUEVO = """
            {"simbolo":"NUEVO","nombre":"Nueva Empresa","sector":"Energía",
             "precioInicial":5000,"tick":10,"accionesEmitidas":1000000}""";
    private static final String EDICION = """
            {"nombre":"Andex Energía S.A.","sector":"Energía renovable"}""";

    @DynamicPropertySource
    static void jwksDePrueba(DynamicPropertyRegistry propiedades) {
        TokensDePrueba.configurar(propiedades);
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ServicioInstrumentos servicio;

    private final Instrumento nuevo = new Instrumento("NUEVO", "Nueva Empresa", "Energía", 5_000, 10, 1_000_000,
            Instant.now());

    @Test
    void unAdminCreaUnInstrumentoYRecibe201ConLaUbicacion() throws Exception {
        when(servicio.crear(any())).thenReturn(nuevo);

        pedir(post("/api/instrumentos").contentType(MediaType.APPLICATION_JSON).content(NUEVO), "ADMIN")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/instrumentos/NUEVO"))
                .andExpect(jsonPath("$.simbolo").value("NUEVO"))
                .andExpect(jsonPath("$.tick").value(10))
                .andExpect(jsonPath("$.accionesEmitidas").value(1_000_000))
                .andExpect(jsonPath("$.estado").value("ACTIVO"));
    }

    @Test
    void crearUnSimboloRepetidoResponde409() throws Exception {
        when(servicio.crear(any())).thenThrow(new InstrumentoDuplicadoException());

        pedir(post("/api/instrumentos").contentType(MediaType.APPLICATION_JSON).content(NUEVO), "ADMIN")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Ese símbolo ya existe"));
    }

    @Test
    void crearConDatosInvalidosResponde400DiciendoQueCorregir() throws Exception {
        String invalido = """
                {"simbolo":"AND1","nombre":"","sector":"Energía","precioInicial":0,"tick":10,"accionesEmitidas":-5}""";

        pedir(post("/api/instrumentos").contentType(MediaType.APPLICATION_JSON).content(invalido), "ADMIN")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(allOf(
                        containsString("simbolo: debe tener de 3 a 5 letras"),
                        containsString("nombre"),
                        containsString("precioInicial"),
                        containsString("accionesEmitidas"))));
        verifyNoInteractions(servicio);
    }

    @Test
    void crearConUnPrecioQueNoRespetaElTickResponde400() throws Exception {
        when(servicio.crear(any())).thenThrow(new InstrumentoInvalidoException("precioInicial debe ser múltiplo del tick (10)."));

        pedir(post("/api/instrumentos").contentType(MediaType.APPLICATION_JSON).content(NUEVO), "ADMIN")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("precioInicial debe ser múltiplo del tick (10)."));
    }

    @Test
    void crearConEstadoResponde400PorqueTodoInstrumentoNaceActivo() throws Exception {
        String conEstado = NUEVO.replace("}", ",\"estado\":\"SUSPENDIDO\"}");

        pedir(post("/api/instrumentos").contentType(MediaType.APPLICATION_JSON).content(conEstado), "ADMIN")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(startsWith("estado no se envía")));
        verifyNoInteractions(servicio);
    }

    @Test
    void unAdminEditaNombreYSector() throws Exception {
        Instrumento andx = new Instrumento("ANDX", "Andex Energía S.A.", "Energía renovable", 5_000, 10, 1_000_000,
                Instant.now());
        when(servicio.editar(eq("ANDX"), any())).thenReturn(andx);

        pedir(put("/api/instrumentos/ANDX").contentType(MediaType.APPLICATION_JSON).content(EDICION), "ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Andex Energía S.A."));
        verify(servicio).editar("ANDX", new EditarInstrumentoRequest("Andex Energía S.A.", "Energía renovable"));
    }

    @ParameterizedTest
    @CsvSource({"tick", "precioInicial", "accionesEmitidas", "simbolo"})
    void editarUnCampoFijoResponde400DiciendoQueNoSePuedeCambiar(String campo) throws Exception {
        String conCampoFijo = EDICION.replace("}", ",\"" + campo + "\":20}");

        pedir(put("/api/instrumentos/ANDX").contentType(MediaType.APPLICATION_JSON).content(conCampoFijo), "ADMIN")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(campo
                        + " no se puede cambiar: después de crear un instrumento solo se editan nombre y sector."));
        verifyNoInteractions(servicio);
    }

    @Test
    void editarConUnCampoQueNoExisteResponde400SinRepetirlo() throws Exception {
        pedir(put("/api/instrumentos/ANDX").contentType(MediaType.APPLICATION_JSON)
                        .content(EDICION.replace("}", ",\"<script>\":1}")), "ADMIN")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Solo se pueden editar nombre y sector."));
        verifyNoInteractions(servicio);
    }

    @Test
    void unAdminSuspendeYReactiva() throws Exception {
        Instrumento andx = new Instrumento("ANDX", "Andex Energía", "Energía", 5_000, 10, 1_000_000, Instant.now());
        andx.suspender(Instant.now());
        when(servicio.suspender("ANDX")).thenReturn(andx);

        pedir(post("/api/instrumentos/ANDX/suspender"), "ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("SUSPENDIDO"));

        Instrumento reactivado = new Instrumento("ANDX", "Andex Energía", "Energía", 5_000, 10, 1_000_000, Instant.now());
        when(servicio.reactivar("ANDX")).thenReturn(reactivado);

        pedir(post("/api/instrumentos/ANDX/reactivar"), "ADMIN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("ACTIVO"));
    }

    @Test
    void suspenderUnInstrumentoQueNoExisteResponde404() throws Exception {
        when(servicio.suspender("NADA")).thenThrow(new InstrumentoNoEncontradoException("NADA"));

        pedir(post("/api/instrumentos/NADA/suspender"), "ADMIN")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("El instrumento NADA no existe."));
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /api/instrumentos",
            "PUT,  /api/instrumentos/ANDX",
            "POST, /api/instrumentos/ANDX/suspender",
            "POST, /api/instrumentos/ANDX/reactivar"})
    void sinTokenNadieGestionaInstrumentos(String metodo, String ruta) throws Exception {
        mvc.perform(gestion(metodo, ruta))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.detail").value(startsWith("Falta el token de acceso")));
        verifyNoInteractions(servicio);
    }

    @ParameterizedTest
    @CsvSource({
            "POST, /api/instrumentos,                  INVERSIONISTA",
            "PUT,  /api/instrumentos/ANDX,             INVERSIONISTA",
            "POST, /api/instrumentos/ANDX/suspender,   INVERSIONISTA",
            "POST, /api/instrumentos/ANDX/reactivar,   INVERSIONISTA",
            "POST, /api/instrumentos,                  BOT",
            "POST, /api/instrumentos/ANDX/suspender,   BOT"})
    void quienNoEsAdminRecibe403(String metodo, String ruta, String rol) throws Exception {
        pedir(gestion(metodo, ruta), rol)
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)))
                .andExpect(jsonPath("$.detail").value("Solo un administrador puede gestionar instrumentos."));
        verifyNoInteractions(servicio);
    }

    @Test
    void unTokenDeAdminFirmadoConOtraClaveResponde401() throws Exception {
        // Alguien se fabrica un token con rol ADMIN: dice ser de la clave de auth-service, pero lo firmó otra
        RSAKey falsa = new RSAKey.Builder(TokensDePrueba.generarClave()).keyID(TokensDePrueba.CLAVE.getKeyID()).build();
        String falsificado = TokensDePrueba.firmar(falsa, TokensDePrueba.EMISOR, "ADMIN",
                Instant.now(), Instant.now().plus(Duration.ofMinutes(15)));

        rechazado(mvc.perform(gestion("POST", "/api/instrumentos")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + falsificado)));
    }

    @Test
    void unTokenDeAdminVencidoResponde401() throws Exception {
        Instant hace20Minutos = Instant.now().minus(Duration.ofMinutes(20));
        String vencido = TokensDePrueba.firmar(TokensDePrueba.CLAVE, TokensDePrueba.EMISOR, "ADMIN",
                hace20Minutos, hace20Minutos.plus(Duration.ofMinutes(15)));

        rechazado(mvc.perform(gestion("POST", "/api/instrumentos/ANDX/suspender")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + vencido)));
    }

    @Test
    void cualquierUsuarioConTokenConsultaElCatalogo() throws Exception {
        when(servicio.listar()).thenReturn(List.of(nuevo));
        when(servicio.buscar("NUEVO")).thenReturn(nuevo);

        pedir(get("/api/instrumentos"), "INVERSIONISTA")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].simbolo").value("NUEVO"));
        pedir(get("/api/instrumentos/NUEVO"), "BOT")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Nueva Empresa"));
    }

    @Test
    void consultarElCatalogoSinTokenResponde401() throws Exception {
        mvc.perform(get("/api/instrumentos")).andExpect(status().isUnauthorized());
        verifyNoInteractions(servicio);
    }

    @Test
    void consultarUnSimboloQueNoExisteResponde404() throws Exception {
        when(servicio.buscar("NADA")).thenThrow(new InstrumentoNoEncontradoException("NADA"));

        pedir(get("/api/instrumentos/NADA"), "INVERSIONISTA")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Instrumento no encontrado"));
    }

    @Test
    void unaRutaQueNoExisteTambienPideToken() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
    }

    private ResultActions pedir(MockHttpServletRequestBuilder peticion, String rol) throws Exception {
        return mvc.perform(peticion.header(HttpHeaders.AUTHORIZATION, "Bearer " + TokensDePrueba.valido(rol)));
    }

    /** Una petición de gestión con un cuerpo válido, para que lo único que falle sea el permiso. */
    private static MockHttpServletRequestBuilder gestion(String metodo, String ruta) {
        String cuerpo = metodo.equals("PUT") ? EDICION : NUEVO;
        return request(HttpMethod.valueOf(metodo), ruta).contentType(MediaType.APPLICATION_JSON).content(cuerpo);
    }

    private void rechazado(ResultActions respuesta) throws Exception {
        respuesta.andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""))
                .andExpect(jsonPath("$.detail").value(startsWith("El token de acceso no es válido o ya venció")));
        verifyNoInteractions(servicio);
    }
}
