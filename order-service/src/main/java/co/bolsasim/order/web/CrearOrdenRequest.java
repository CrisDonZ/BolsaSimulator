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
 * <p>usuarioId llega en el cuerpo solo mientras no exista el login.
 * Cuando esté auth-service, saldrá del token JWT y se quitará de aquí.
 */
public record CrearOrdenRequest(
        @NotBlank String usuarioId,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3,5}", message = "debe tener de 3 a 5 letras") String simbolo,
        @NotNull Lado lado,
        @NotNull TipoOrden tipo,
        @Positive Long precioLimite,
        @Positive int cantidad) {
}
