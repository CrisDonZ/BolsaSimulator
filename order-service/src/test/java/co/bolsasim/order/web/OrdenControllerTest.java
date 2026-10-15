package co.bolsasim.order.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.Lado;
import co.bolsasim.eventos.TipoOrden;
import co.bolsasim.order.servicio.ServicioOrdenes;
import co.bolsasim.order.servicio.UsuarioNoRegistradoException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Prueba del controlador: levanta solo la capa web (rutas, validaciones, JSON y manejo de errores).
 * El servicio es un doble de Mockito, así no hacen falta Kafka ni base de datos.
 */
@WebMvcTest(OrdenController.class)
class OrdenControllerTest {

    private static final String USUARIO = UUID.randomUUID().toString();

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ServicioOrdenes servicio;

    @Test
    void ordenDeUnUsuarioRegistradoResponde202ConElIdDeLaOrden() throws Exception {
        ComandoOrden comando = ComandoOrden.nueva(USUARIO, "ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, 100);
        when(servicio.crear(any())).thenReturn(comando);

        mvc.perform(post("/api/ordenes").contentType(MediaType.APPLICATION_JSON).content(orden(USUARIO)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.ordenId").value(comando.ordenId().toString()))
                .andExpect(jsonPath("$.simbolo").value("ANDX"));
    }

    @Test
    void ordenDeUnUsuarioDesconocidoResponde422ConElMotivo() throws Exception {
        String desconocido = UUID.randomUUID().toString();
        when(servicio.crear(any())).thenThrow(new UsuarioNoRegistradoException(desconocido));

        mvc.perform(post("/api/ordenes").contentType(MediaType.APPLICATION_JSON).content(orden(desconocido)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Usuario no registrado"))
                .andExpect(jsonPath("$.detail").value(containsString(desconocido + " no está registrado")));
    }

    @Test
    void ordenSinUsuarioResponde400YNoLlegaAlServicio() throws Exception {
        mvc.perform(post("/api/ordenes").contentType(MediaType.APPLICATION_JSON).content(orden("")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("usuarioId")));
        verifyNoInteractions(servicio);
    }

    private static String orden(String usuarioId) {
        return """
                {"usuarioId":"%s","simbolo":"ANDX","lado":"COMPRA","tipo":"LIMITE","precioLimite":5000,"cantidad":100}
                """.formatted(usuarioId);
    }
}
