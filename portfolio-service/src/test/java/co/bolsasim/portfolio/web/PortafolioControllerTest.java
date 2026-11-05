package co.bolsasim.portfolio.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.bolsasim.portfolio.TokensDePrueba;
import co.bolsasim.portfolio.cuenta.Cuenta;
import co.bolsasim.portfolio.posicion.PosicionConPrecio;
import co.bolsasim.portfolio.seguridad.ConfiguracionSeguridad;
import co.bolsasim.portfolio.seguridad.RespuestaNoAutorizado;
import co.bolsasim.portfolio.servicio.Portafolio;
import co.bolsasim.portfolio.servicio.PosicionValorada;
import co.bolsasim.portfolio.servicio.ServicioPortafolio;
import co.bolsasim.portfolio.servicio.SinCuentaException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Como {@link CuentaControllerTest}: la capa web con la seguridad real y el servicio como doble de Mockito. */
@WebMvcTest(PortafolioController.class)
@Import({ConfiguracionSeguridad.class, RespuestaNoAutorizado.class})
class PortafolioControllerTest {

    private static final UUID ANA = UUID.randomUUID();

    @DynamicPropertySource
    static void jwksDePrueba(DynamicPropertyRegistry propiedades) {
        TokensDePrueba.configurar(propiedades);
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ServicioPortafolio servicio;

    @Test
    void elPortafolioEsElDelUsuarioDelTokenConCadaPosicionValorada() throws Exception {
        Cuenta cuenta = new Cuenta(UUID.randomUUID(), ANA, 10_000_000, 8_900_000, 0, Instant.now());
        when(servicio.consultar(ANA, "INVERSIONISTA")).thenReturn(new Portafolio(cuenta, List.of(PosicionValorada.de(
                new PosicionConPrecio("ANDX", 200, 50, new BigDecimal("5500.0000"), 6_000)))));

        mvc.perform(get("/api/portafolio").header(HttpHeaders.AUTHORIZATION, "Bearer " + TokensDePrueba.de(ANA, "INVERSIONISTA")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saldoDisponible").value(8_900_000))
                .andExpect(jsonPath("$.saldoReservado").value(0))
                .andExpect(jsonPath("$.saldoTotal").value(8_900_000))
                .andExpect(jsonPath("$.valorAcciones").value(1_200_000))
                .andExpect(jsonPath("$.valorTotal").value(10_100_000))
                .andExpect(jsonPath("$.posiciones[0].simbolo").value("ANDX"))
                .andExpect(jsonPath("$.posiciones[0].cantidad").value(200))
                .andExpect(jsonPath("$.posiciones[0].cantidadReservada").value(50))
                .andExpect(jsonPath("$.posiciones[0].precioPromedio").value(5500.0))
                .andExpect(jsonPath("$.posiciones[0].precioActual").value(6_000))
                .andExpect(jsonPath("$.posiciones[0].costo").value(1_100_000))
                .andExpect(jsonPath("$.posiciones[0].valorActual").value(1_200_000))
                .andExpect(jsonPath("$.posiciones[0].ganancia").value(100_000))
                .andExpect(jsonPath("$.posiciones[0].gananciaPorcentaje").value(9.09))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.usuarioId").doesNotExist());
    }

    @Test
    void unAdminRecibe404() throws Exception {
        when(servicio.consultar(any(), eq("ADMIN"))).thenThrow(new SinCuentaException());

        mvc.perform(get("/api/portafolio").header(HttpHeaders.AUTHORIZATION, "Bearer " + TokensDePrueba.valido("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Los administradores no tienen cuenta de inversión."));
    }

    @Test
    void sinTokenResponde401YNoConsultaNada() throws Exception {
        mvc.perform(get("/api/portafolio")).andExpect(status().isUnauthorized());
        verifyNoInteractions(servicio);
    }
}
