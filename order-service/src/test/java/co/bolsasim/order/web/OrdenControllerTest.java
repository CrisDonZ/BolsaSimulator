package co.bolsasim.order.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.bolsasim.eventos.AccionComando;
import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.EstadoInstrumento;
import co.bolsasim.eventos.EstadoOrden;
import co.bolsasim.eventos.InstrumentoActualizado;
import co.bolsasim.eventos.Lado;
import co.bolsasim.eventos.SolicitudReserva;
import co.bolsasim.eventos.TipoOrden;
import co.bolsasim.eventos.Topicos;
import co.bolsasim.order.TokensDePrueba;
import co.bolsasim.order.instrumento.Instrumento;
import co.bolsasim.order.instrumento.RepositorioInstrumentos;
import co.bolsasim.order.orden.ConsultaOrdenes;
import co.bolsasim.order.orden.Cursor;
import co.bolsasim.order.orden.FiltroEstado;
import co.bolsasim.order.orden.Orden;
import co.bolsasim.order.orden.RepositorioOrdenes;
import co.bolsasim.order.outbox.Outbox;
import co.bolsasim.order.seguridad.ConfiguracionSeguridad;
import co.bolsasim.order.seguridad.RespuestaNoAutorizado;
import co.bolsasim.order.servicio.ServicioOrdenes;
import co.bolsasim.order.usuario.RepositorioUsuarios;
import com.nimbusds.jose.jwk.RSAKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Prueba de la capa web con la seguridad real: los tokens se firman con la clave de prueba y
 * order-service los valida contra el JWKS de {@link TokensDePrueba}, como haría con auth-service.
 * El servicio de órdenes también es el real; el outbox, los repositorios y las copias locales de usuarios
 * e instrumentos son dobles de Mockito, así se puede ver qué comando saldría hacia el motor sin levantar Kafka
 * ni base de datos.
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
    private Outbox outbox;

    @MockitoBean
    private RepositorioOrdenes ordenes;

    @MockitoBean
    private ConsultaOrdenes consulta;

    @MockitoBean
    private RepositorioUsuarios usuarios;

    @MockitoBean
    private RepositorioInstrumentos instrumentos;

    // @WebMvcTest no levanta la base: las transacciones del servicio pasan por este doble
    @MockitoBean
    private PlatformTransactionManager transacciones;

    @BeforeEach
    void elUsuarioDelTokenEstaRegistradoYAndxEstaActivo() {
        when(usuarios.existsById(UUID.fromString(USUARIO))).thenReturn(true);
        when(instrumentos.findById("ANDX")).thenReturn(Optional.of(instrumento("ANDX", EstadoInstrumento.ACTIVO)));
        when(ordenes.saveAndFlush(any())).thenAnswer(invocacion -> invocacion.getArgument(0));
    }

    @Test
    void sinIdempotencyKeyResponde400YNoGuardaNada() throws Exception {
        enviar(ORDEN, TokensDePrueba.valido(USUARIO), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Clave de idempotencia inválida"))
                .andExpect(jsonPath("$.detail").value(startsWith("Falta el header Idempotency-Key")));
        verifyNoInteractions(outbox, ordenes);
    }

    @Test
    void elReintentoConLaMismaClaveRecibeLaRespuestaOriginalMarcadaComoRepetida() throws Exception {
        String clave = UUID.randomUUID().toString();
        Orden original = ordenDe(USUARIO, clave);
        when(ordenes.findByUsuarioIdAndClaveIdempotencia(UUID.fromString(USUARIO), clave)).thenReturn(Optional.of(original));

        enviar(ORDEN, TokensDePrueba.valido(USUARIO), clave)
                .andExpect(status().isAccepted())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/ordenes/" + original.getId()))
                .andExpect(header().string(OrdenController.IDEMPOTENT_REPLAYED, "true"))
                .andExpect(jsonPath("$.ordenId").value(original.getId().toString()))
                .andExpect(jsonPath("$.mensaje").value("Orden recibida: se reserva tu saldo y después va al motor"));
        verifyNoInteractions(outbox);
    }

    @Test
    void unaOrdenNuevaNoTraeElHeaderDeRepetida() throws Exception {
        enviar(ORDEN, TokensDePrueba.valido(USUARIO))
                .andExpect(status().isAccepted())
                .andExpect(header().doesNotExist(OrdenController.IDEMPOTENT_REPLAYED));
    }

    @Test
    void laMismaClaveConOtraOrdenResponde422() throws Exception {
        String clave = UUID.randomUUID().toString();
        when(ordenes.findByUsuarioIdAndClaveIdempotencia(UUID.fromString(USUARIO), clave))
                .thenReturn(Optional.of(ordenDe(USUARIO, clave)));

        enviar(ORDEN.replace("\"cantidad\":100", "\"cantidad\":300"), TokensDePrueba.valido(USUARIO), clave)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Clave de idempotencia ya usada"));
        verifyNoInteractions(outbox);
    }

    @Test
    void unaOrdenAceptadaTraeLaUbicacionParaConsultarla() throws Exception {
        var respuesta = enviar(ORDEN, TokensDePrueba.valido(USUARIO))
                .andExpect(status().isAccepted())
                .andReturn().getResponse();
        String ordenId = respuesta.getContentAsString().replaceAll(".*\"ordenId\":\"([^\"]+)\".*", "$1");

        assertThat(respuesta.getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/ordenes/" + ordenId);
    }

    @Test
    void consultarSuOrdenResponde200ConElEstado() throws Exception {
        Orden orden = ordenDe(USUARIO);
        when(ordenes.findByIdAndUsuarioId(orden.getId(), UUID.fromString(USUARIO))).thenReturn(Optional.of(orden));

        consultar("/api/ordenes/" + orden.getId(), TokensDePrueba.valido(USUARIO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orden.getId().toString()))
                .andExpect(jsonPath("$.estado").value("PENDIENTE"))
                .andExpect(jsonPath("$.cantidadEjecutada").value(0))
                .andExpect(jsonPath("$.precioPromedio").isEmpty())
                .andExpect(jsonPath("$.usuarioId").doesNotExist())
                .andExpect(jsonPath("$.version").doesNotExist());
    }

    @Test
    void laOrdenDeOtroResponde404IgualQueUnaQueNoExiste() throws Exception {
        Orden deBeto = ordenDe(UUID.randomUUID().toString());
        UUID inexistente = UUID.randomUUID();
        String tokenDeAna = TokensDePrueba.valido(USUARIO);

        String deOtro = consultar("/api/ordenes/" + deBeto.getId(), tokenDeAna)
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String deNadie = consultar("/api/ordenes/" + inexistente, tokenDeAna)
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        // Lo mismo en los dos casos, salvo el id que Ana pidió: no sabe que la orden de Beto existe
        assertThat(deOtro).isEqualTo(deNadie.replace(inexistente.toString(), deBeto.getId().toString()))
                .contains("\"title\":\"Orden no encontrada\"");
    }

    @Test
    void laListaTraeLasOrdenesYElCursorDeLaSiguientePagina() throws Exception {
        List<Orden> tres = List.of(ordenDe(USUARIO), ordenDe(USUARIO), ordenDe(USUARIO));
        when(consulta.buscar(UUID.fromString(USUARIO), FiltroEstado.TODAS, null, 3)).thenReturn(tres);

        consultar("/api/ordenes?tamano=2", TokensDePrueba.valido(USUARIO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ordenes.length()").value(2))
                .andExpect(jsonPath("$.ordenes[0].id").value(tres.get(0).getId().toString()))
                .andExpect(jsonPath("$.siguiente").value(Cursor.despuesDe(tres.get(1)).codificar()))
                .andExpect(jsonPath("$.totalElementos").doesNotExist());
    }

    @Test
    void laListaSinTamanoUsa20YPideUnaFilaDeMas() throws Exception {
        consultar("/api/ordenes?estado=ABIERTAS", TokensDePrueba.valido(USUARIO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ordenes").isEmpty())
                .andExpect(jsonPath("$.siguiente").isEmpty());
        verify(consulta).buscar(UUID.fromString(USUARIO), FiltroEstado.ABIERTAS, null, 21);
    }

    @Test
    void parametrosInvalidosDeLaListaResponden400() throws Exception {
        String token = TokensDePrueba.valido(USUARIO);

        consultar("/api/ordenes?tamano=101", token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("tamano debe estar entre 1 y 100."));
        consultar("/api/ordenes?tamano=muchas", token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("tamano no tiene el formato esperado."));
        consultar("/api/ordenes?estado=VIVAS", token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(startsWith("estado debe ser uno de: ABIERTAS")));
        consultar("/api/ordenes?despues=alterado", token)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(startsWith("despues no es válido")));
        consultar("/api/ordenes/no-es-un-id", token)
                .andExpect(status().isBadRequest());
        verifyNoInteractions(consulta, ordenes);
    }

    @Test
    void consultarSinTokenResponde401() throws Exception {
        consultar("/api/ordenes", null).andExpect(status().isUnauthorized());
        consultar("/api/ordenes/" + UUID.randomUUID(), null).andExpect(status().isUnauthorized());
        verifyNoInteractions(consulta, ordenes);
    }

    @Test
    void unaOrdenDeUnInstrumentoSuspendidoResponde422YNoSeGuarda() throws Exception {
        when(instrumentos.findById("ANDX")).thenReturn(Optional.of(instrumento("ANDX", EstadoInstrumento.SUSPENDIDO)));

        enviar(ORDEN, TokensDePrueba.valido(USUARIO))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Instrumento no disponible"))
                .andExpect(jsonPath("$.detail").value("El instrumento ANDX está suspendido: no recibe órdenes nuevas."));
        verifyNoInteractions(outbox);
    }

    @Test
    void unaOrdenDeUnInstrumentoQueNoExisteResponde422YNoSeGuarda() throws Exception {
        enviar(ORDEN.replace("ANDX", "NADA"), TokensDePrueba.valido(USUARIO))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Instrumento no disponible"))
                .andExpect(jsonPath("$.detail").value("El instrumento NADA no existe."));
        verifyNoInteractions(outbox);
    }

    @Test
    void conUnTokenValidoLaSolicitudDeReservaQuedaEnElOutboxANombreDelSubDelToken() throws Exception {
        enviar(ORDEN, TokensDePrueba.valido(USUARIO))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.simbolo").value("ANDX"))
                .andExpect(jsonPath("$.ordenId").isNotEmpty());

        ArgumentCaptor<Object> solicitud = ArgumentCaptor.forClass(Object.class);
        verify(outbox).guardar(eq(Topicos.ORDERS_REQUESTED), eq(USUARIO), solicitud.capture());
        assertThat(((SolicitudReserva) solicitud.getValue()).usuarioId()).isEqualTo(USUARIO);
        assertThat(((SolicitudReserva) solicitud.getValue()).simbolo()).isEqualTo("ANDX");
    }

    @Test
    void unPrecioFueraDelTickODeLaBandaResponde422YNoSeGuarda() throws Exception {
        enviar(ORDEN.replace("5000", "5005"), TokensDePrueba.valido(USUARIO))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Precio no válido"))
                .andExpect(jsonPath("$.detail").value(startsWith("El precio 5.005 no es múltiplo del tick de ANDX")));
        enviar(ORDEN.replace("5000", "6000"), TokensDePrueba.valido(USUARIO))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(containsString("debe estar entre 4.500 y 5.500")));
        verifyNoInteractions(outbox);
    }

    @Test
    void sinTokenResponde401YNoSeGuarda() throws Exception {
        enviar(ORDEN, null)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)))
                .andExpect(jsonPath("$.detail").value(startsWith("Falta el token de acceso")));
        verifyNoInteractions(outbox);
    }

    @Test
    void cancelarUnaOrdenAbiertaResponde202ConLaOrdenYEnviaElCancelar() throws Exception {
        Orden abierta = ordenDe(USUARIO);
        abierta.aplicar(EstadoOrden.ABIERTA, 100, 0, null, null, Instant.now());
        when(ordenes.solicitarCancelacion(eq(abierta.getId()), eq(UUID.fromString(USUARIO)), any())).thenAnswer(
                invocacion -> {
                    ReflectionTestUtils.setField(abierta, "cancelacionSolicitadaEn", invocacion.getArgument(2));
                    return 1;
                });
        when(ordenes.findByIdAndUsuarioId(abierta.getId(), UUID.fromString(USUARIO))).thenReturn(Optional.of(abierta));

        cancelar(abierta.getId(), TokensDePrueba.valido(USUARIO))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(abierta.getId().toString()))
                // Sigue ABIERTA: queda CANCELADA cuando el motor lo confirma
                .andExpect(jsonPath("$.estado").value("ABIERTA"))
                .andExpect(jsonPath("$.cancelacionSolicitadaEn").isNotEmpty());

        ArgumentCaptor<Object> comando = ArgumentCaptor.forClass(Object.class);
        verify(outbox).guardar(eq(Topicos.ORDERS_COMMANDS), eq("ANDX"), comando.capture());
        assertThat(((ComandoOrden) comando.getValue()).accion()).isEqualTo(AccionComando.CANCELAR);
    }

    @Test
    void cancelarUnaOrdenQueNoExisteODeOtroResponde404() throws Exception {
        cancelar(UUID.randomUUID(), TokensDePrueba.valido(USUARIO))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Orden no encontrada"));
        verifyNoInteractions(outbox);
    }

    @Test
    void cancelarUnaOrdenPendienteOTerminadaResponde409ConElMotivo() throws Exception {
        Orden pendiente = ordenDe(USUARIO);
        Orden ejecutada = ordenDe(USUARIO);
        ejecutada.aplicar(EstadoOrden.EJECUTADA, 100, 100, null, null, Instant.now());
        when(ordenes.findByIdAndUsuarioId(pendiente.getId(), UUID.fromString(USUARIO))).thenReturn(Optional.of(pendiente));
        when(ordenes.findByIdAndUsuarioId(ejecutada.getId(), UUID.fromString(USUARIO))).thenReturn(Optional.of(ejecutada));

        cancelar(pendiente.getId(), TokensDePrueba.valido(USUARIO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Orden no cancelable"))
                .andExpect(jsonPath("$.detail")
                        .value("La orden todavía se está procesando; intenta cancelarla en unos segundos."));
        cancelar(ejecutada.getId(), TokensDePrueba.valido(USUARIO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("La orden ya terminó (EJECUTADA): no se puede cancelar."));
        verifyNoInteractions(outbox);
    }

    @Test
    void cancelarSinTokenResponde401() throws Exception {
        cancelar(UUID.randomUUID(), null)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        verifyNoInteractions(outbox, ordenes);
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
    void siElCuerpoTraeUsuarioIdResponde400YNoSeGuarda() throws Exception {
        // Aunque el token sea válido, nadie puede poner una orden a nombre de otro usuario
        String aNombreDeOtro = """
                {"usuarioId":"%s","simbolo":"ANDX","lado":"COMPRA","tipo":"LIMITE","precioLimite":5000,"cantidad":100}"""
                .formatted(UUID.randomUUID());

        enviar(aNombreDeOtro, TokensDePrueba.valido(USUARIO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(
                        "usuarioId ya no se envía: el usuario de la orden sale del token de acceso."));
        verifyNoInteractions(outbox);
    }

    @Test
    void unCampoQueNoExisteResponde400SinRepetirloEnLaRespuesta() throws Exception {
        String conCampoDeMas = """
                {"simbolo":"ANDX","lado":"COMPRA","tipo":"LIMITE","precioLimite":5000,"cantidad":100,"comision":0}""";

        enviar(conCampoDeMas, TokensDePrueba.valido(USUARIO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(startsWith("La orden trae un campo que no existe")));
        verifyNoInteractions(outbox);
    }

    @Test
    void elClienteNoPuedeElegirElPrecioDeReferenciaDeUnaOrdenAMercado() throws Exception {
        // Con una referencia inventada, una compra a mercado reservaría poco y el motor la dejaría ejecutar caro: la
        // referencia solo la pone order-service (RF-26)
        String conReferencia = """
                {"simbolo":"ANDX","lado":"COMPRA","tipo":"MERCADO","precioReferencia":1,"cantidad":100}""";

        enviar(conReferencia, TokensDePrueba.valido(USUARIO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(startsWith("La orden trae un campo que no existe")));
        verifyNoInteractions(outbox);
    }

    @Test
    void unLadoQueNoExisteResponde400() throws Exception {
        enviar(ORDEN.replace("COMPRA", "REGALO"), TokensDePrueba.valido(USUARIO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("COMPRA o VENTA")));
        verifyNoInteractions(outbox);
    }

    @Test
    void unaCantidadInvalidaResponde400ConElCampo() throws Exception {
        enviar(ORDEN.replace("\"cantidad\":100", "\"cantidad\":0"), TokensDePrueba.valido(USUARIO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("cantidad")));
        verifyNoInteractions(outbox);
    }

    @Test
    void unTokenValidoDeUnUsuarioQueAunNoEstaEnLaCopiaLocalResponde422() throws Exception {
        // Inició sesión antes de que users.registered llegara a order-service
        String recienRegistrado = UUID.randomUUID().toString();

        enviar(ORDEN, TokensDePrueba.valido(recienRegistrado))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Usuario no registrado"))
                .andExpect(jsonPath("$.detail").value(containsString(recienRegistrado + " no está registrado")));
        verifyNoInteractions(outbox);
    }

    /** Envía la orden con una Idempotency-Key nueva, como un cliente que crea una orden distinta cada vez. */
    private ResultActions enviar(String cuerpo, String token) throws Exception {
        return enviar(cuerpo, token, UUID.randomUUID().toString());
    }

    private ResultActions enviar(String cuerpo, String token, String clave) throws Exception {
        var peticion = post("/api/ordenes").contentType(MediaType.APPLICATION_JSON).content(cuerpo);
        if (token != null) {
            peticion.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        if (clave != null) {
            peticion.header(OrdenController.IDEMPOTENCY_KEY, clave);
        }
        return mvc.perform(peticion);
    }

    private static Instrumento instrumento(String simbolo, EstadoInstrumento estado) {
        return new Instrumento(new InstrumentoActualizado(simbolo, "Andex Energía", "Energía", 5_000, 10, 1_000_000,
                estado, Instant.now()));
    }

    private ResultActions cancelar(UUID ordenId, String token) throws Exception {
        var peticion = post("/api/ordenes/" + ordenId + "/cancelacion");
        if (token != null) {
            peticion.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(peticion);
    }

    private ResultActions consultar(String ruta, String token) throws Exception {
        var peticion = get(ruta);
        if (token != null) {
            peticion.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(peticion);
    }

    /** Una orden PENDIENTE de ese usuario, como si viniera de la base. */
    private static Orden ordenDe(String usuarioId) {
        return ordenDe(usuarioId, UUID.randomUUID().toString());
    }

    /** La misma orden que ORDEN (compra LIMITE de 100 ANDX a 5.000), creada con esa clave. */
    private static Orden ordenDe(String usuarioId, String clave) {
        return Orden.nueva(ComandoOrden.nueva(usuarioId, "ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100),
                UUID.fromString(usuarioId), clave, Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    private void rechazado(ResultActions respuesta) throws Exception {
        respuesta.andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\""))
                .andExpect(jsonPath("$.detail").value(startsWith("El token de acceso no es válido o ya venció")));
        verifyNoInteractions(outbox);
    }
}
