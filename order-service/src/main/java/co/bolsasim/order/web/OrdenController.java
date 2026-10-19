package co.bolsasim.order.web;

import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.order.servicio.ServicioOrdenes;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ordenes")
public class OrdenController {

    private final ServicioOrdenes servicioOrdenes;

    public OrdenController(ServicioOrdenes servicioOrdenes) {
        this.servicioOrdenes = servicioOrdenes;
    }

    /**
     * Responde 202 Accepted y no 201 Created: la orden se recibió, pero el motor
     * la procesa después, de forma asíncrona, cuando la lea de Kafka.
     *
     * <p>La orden es del usuario del token ("sub"), que ya validó la cadena de seguridad.
     */
    @PostMapping
    public ResponseEntity<OrdenAceptadaResponse> crear(@AuthenticationPrincipal Jwt token,
                                                       @Valid @RequestBody CrearOrdenRequest request) {
        ComandoOrden comando = servicioOrdenes.crear(token.getSubject(), request);
        return ResponseEntity.accepted()
                .body(new OrdenAceptadaResponse(comando.ordenId(), comando.simbolo(), "Orden enviada al motor"));
    }
}
