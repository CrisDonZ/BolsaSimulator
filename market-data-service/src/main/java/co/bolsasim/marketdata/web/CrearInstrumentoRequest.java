package co.bolsasim.marketdata.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Lo que el administrador envía en el cuerpo de POST /api/instrumentos (HU-22, RF-54). El estado no se
 * envía: todo instrumento nace ACTIVO. Los montos van en COP, sin decimales.
 */
public record CrearInstrumentoRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z]{3,5}", message = "debe tener de 3 a 5 letras") String simbolo,
        @NotBlank @Size(max = 100) String nombre,
        @NotBlank @Size(max = 50) String sector,
        @NotNull @Positive Long precioInicial,
        @NotNull @Positive Long tick,
        @NotNull @Positive Long accionesEmitidas) {
}
