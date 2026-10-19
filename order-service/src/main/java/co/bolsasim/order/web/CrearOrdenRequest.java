package co.bolsasim.order.web;

import co.bolsasim.eventos.Lado;
import co.bolsasim.eventos.TipoOrden;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/**
 * Lo que el cliente envía en el cuerpo de POST /api/ordenes.
 *
 * <p>No lleva el id del usuario: sale del token de acceso (claim "sub"), así nadie puede poner
 * órdenes a nombre de otro (RNF-23). Si el cuerpo trae usuarioId, o cualquier campo que no esté
 * aquí, la orden se rechaza con 400.
 */
public record CrearOrdenRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z]{3,5}", message = "debe tener de 3 a 5 letras") String simbolo,
        @NotNull Lado lado,
        @NotNull TipoOrden tipo,
        @Positive Long precioLimite,
        @Positive int cantidad) {
}
