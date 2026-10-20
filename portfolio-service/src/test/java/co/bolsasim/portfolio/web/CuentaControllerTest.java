package co.bolsasim.portfolio.web;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.bolsasim.portfolio.TokensDePrueba;
import co.bolsasim.portfolio.cuenta.Cuenta;
import co.bolsasim.portfolio.movimiento.Movimiento;
import co.bolsasim.portfolio.movimiento.TipoMovimiento;
import co.bolsasim.portfolio.seguridad.ConfiguracionSeguridad;
import co.bolsasim.portfolio.seguridad.RespuestaNoAutorizado;
import co.bolsasim.portfolio.servicio.ConsultaInvalidaException;
import co.bolsasim.portfolio.servicio.PaginaMovimientos;
import co.bolsasim.portfolio.servicio.ServicioCuentas;
import co.bolsasim.portfolio.servicio.SinCuentaException;
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

/**
 * Prueba de la capa web con la seguridad real: los tokens se firman con la clave de prueba y portfolio-service
 * los valida contra el JWKS de {@link TokensDePrueba}. El servicio es un doble de Mockito.
 */
@WebMvcTest(CuentaController.class)
@Import({ConfiguracionSeguridad.class, RespuestaNoAutorizado.class})
class CuentaControllerTest {

    private static final UUID ANA = UUID.randomUUID();

    @DynamicPropertySource
    static void jwksDePrueba(DynamicPropertyRegistry propiedades) {
        TokensDePrueba.configurar(propiedades);
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ServicioCuentas servicio;

    @Test
    void elSaldoEsElDeLaCuentaDelUsuarioDelToken() throws Exception {
        when(servicio.consultar(ANA, "INVERSIONISTA"))
                .thenReturn(new Cuenta(UUID.randomUUID(), ANA, 10_000_000, 9_000_000, 1_000_000, Instant.now()));

        mvc.perform(get("/api/cuenta").header(HttpHeaders.AUTHORIZATION, "Bearer " + TokensDePrueba.de(ANA, "INVERSIONISTA")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saldoDisponible").value(9_000_000))
                .andExpect(jsonPath("$.saldoReservado").value(1_000_000))
                .andExpect(jsonPath("$.saldoTotal").value(10_000_000))
                .andExpect(jsonPath("$.saldoInicial").value(10_000_000))
                .andExpect(jsonPath("$.usuarioId").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void unAdminRecibe404() throws Exception {
        when(servicio.consultar(any(), eq("ADMIN"))).thenThrow(new SinCuentaException());

        mvc.perform(get("/api/cuenta").header(HttpHeaders.AUTHORIZATION, "Bearer " + TokensDePrueba.valido("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Los administradores no tienen cuenta de inversión."));
    }

    @Test
    void losMovimientosPasanSusFiltrosAlServicio() throws Exception {
        Movimiento inicial = new Movimiento(UUID.randomUUID(), UUID.randomUUID(), TipoMovimiento.SALDO_INICIAL,
                10_000_000, null, null, null, Instant.now());
        when(servicio.movimientos(ANA, "INVERSIONISTA", "SALDO_INICIAL", "2026-10-01", "2026-10-20", 5, null))
                .thenReturn(new PaginaMovimientos(List.of(inicial), null));

        mvc.perform(get("/api/cuenta/movimientos?tipo=SALDO_INICIAL&desde=2026-10-01&hasta=2026-10-20&tamano=5")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TokensDePrueba.de(ANA, "INVERSIONISTA")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movimientos[0].tipo").value("SALDO_INICIAL"))
                .andExpect(jsonPath("$.movimientos[0].monto").value(10_000_000))
                .andExpect(jsonPath("$.movimientos[0].cuentaId").doesNotExist())
                .andExpect(jsonPath("$.siguiente").isEmpty());
    }

    @Test
    void sinTamanoSeUsan20() throws Exception {
        when(servicio.movimientos(eq(ANA), anyString(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new PaginaMovimientos(List.of(), null));

        mvc.perform(get("/api/cuenta/movimientos")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TokensDePrueba.de(ANA, "INVERSIONISTA")))
                .andExpect(status().isOk());
        verify(servicio).movimientos(ANA, "INVERSIONISTA", null, null, null, 20, null);
    }

    @Test
    void unaConsultaInvalidaResponde400() throws Exception {
        when(servicio.movimientos(any(), any(), eq("REGALO"), any(), any(), anyInt(), any()))
                .thenThrow(new ConsultaInvalidaException("tipo debe ser uno de: ..."));

        mvc.perform(get("/api/cuenta/movimientos?tipo=REGALO")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TokensDePrueba.de(ANA, "INVERSIONISTA")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Consulta inválida"));
        mvc.perform(get("/api/cuenta/movimientos?tamano=muchos")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TokensDePrueba.de(ANA, "INVERSIONISTA")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("tamano no tiene el formato esperado."));
    }

    @Test
    void sinTokenResponde401YNoConsultaNada() throws Exception {
        mvc.perform(get("/api/cuenta"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.detail").value(startsWith("Falta el token de acceso")));
        mvc.perform(get("/api/cuenta/movimientos")).andExpect(status().isUnauthorized());
        verifyNoInteractions(servicio);
    }

    @Test
    void unTokenFirmadoConOtraClaveResponde401() throws Exception {
        var falsa = new com.nimbusds.jose.jwk.RSAKey.Builder(TokensDePrueba.generarClave())
                .keyID(TokensDePrueba.CLAVE.getKeyID()).build();
        String falsificado = TokensDePrueba.firmar(falsa, TokensDePrueba.EMISOR, ANA.toString(), "INVERSIONISTA",
                Instant.now(), Instant.now().plusSeconds(900));

        mvc.perform(get("/api/cuenta").header(HttpHeaders.AUTHORIZATION, "Bearer " + falsificado))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(servicio);
    }
}
