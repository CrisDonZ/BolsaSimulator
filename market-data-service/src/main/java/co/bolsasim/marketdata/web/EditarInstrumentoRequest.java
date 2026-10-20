package co.bolsasim.marketdata.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de PUT /api/instrumentos/{simbolo}: solo el nombre y el sector se pueden cambiar. Si llega otro
 * campo (tick, precioInicial, accionesEmitidas...), la petición se rechaza con 400 en vez de ignorarlo.
 */
public record EditarInstrumentoRequest(
        @NotBlank @Size(max = 100) String nombre,
        @NotBlank @Size(max = 50) String sector) {
}
