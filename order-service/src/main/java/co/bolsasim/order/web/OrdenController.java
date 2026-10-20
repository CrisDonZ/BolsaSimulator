package co.bolsasim.order.web;

import co.bolsasim.order.orden.Orden;
import co.bolsasim.order.servicio.ServicioOrdenes;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Las órdenes siempre son del usuario del token ("sub"), que ya validó la cadena de seguridad. */
@RestController
@RequestMapping("/api/ordenes")
public class OrdenController {

    private final ServicioOrdenes servicioOrdenes;

    public OrdenController(ServicioOrdenes servicioOrdenes) {
        this.servicioOrdenes = servicioOrdenes;
    }

    /**
     * Responde 202 Accepted y no 201 Created: la orden quedó guardada, pero el motor la procesa después,
     * de forma asíncrona. El header Location dice dónde consultar cómo va.
     */
    @PostMapping
    public ResponseEntity<OrdenAceptadaResponse> crear(@AuthenticationPrincipal Jwt token,
                                                       @Valid @RequestBody CrearOrdenRequest request) {
        Orden orden = servicioOrdenes.crear(token.getSubject(), request);
        return ResponseEntity.accepted()
                .location(URI.create("/api/ordenes/" + orden.getId()))
                .body(new OrdenAceptadaResponse(orden.getId(), orden.getSimbolo(), "Orden recibida: va en camino al motor"));
    }

    /** La orden de otro usuario responde 404, igual que una que no existe (RNF-23). */
    @GetMapping("/{id}")
    public OrdenResponse buscar(@AuthenticationPrincipal Jwt token, @PathVariable UUID id) {
        return OrdenResponse.de(servicioOrdenes.buscar(token.getSubject(), id));
    }

    /**
     * Tus órdenes, de la más reciente a la más vieja (HU-09). Para la página siguiente, envía en despues el
     * valor "siguiente" de esta respuesta.
     *
     * @param estado ABIERTAS (ABIERTA y PARCIAL) o uno de los estados; sin él, todas
     */
    @GetMapping
    public PaginaOrdenesResponse listar(@AuthenticationPrincipal Jwt token,
                                        @RequestParam(required = false) String estado,
                                        @RequestParam(defaultValue = "" + ServicioOrdenes.TAMANO_POR_DEFECTO) int tamano,
                                        @RequestParam(required = false) String despues) {
        return PaginaOrdenesResponse.de(servicioOrdenes.listar(token.getSubject(), estado, tamano, despues));
    }
}
