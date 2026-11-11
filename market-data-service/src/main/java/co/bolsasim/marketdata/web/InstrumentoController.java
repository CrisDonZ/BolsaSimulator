package co.bolsasim.marketdata.web;

import co.bolsasim.marketdata.instrumento.Instrumento;
import co.bolsasim.marketdata.servicio.ServicioInstrumentos;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catálogo de instrumentos, cada uno con su último precio y su variación del día (HU-14). Consultarlo pide un token; crear, editar, suspender y reactivar piden además
 * el rol ADMIN (ver ConfiguracionSeguridad). No hay borrado: las acciones de un instrumento existen
 * mientras exista el mercado (RNF-08).
 */
@RestController
@RequestMapping("/api/instrumentos")
public class InstrumentoController {

    private final ServicioInstrumentos servicio;

    public InstrumentoController(ServicioInstrumentos servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public List<InstrumentoResponse> listar() {
        return servicio.listar().stream().map(InstrumentoResponse::de).toList();
    }

    @GetMapping("/{simbolo}")
    public InstrumentoResponse buscar(@PathVariable String simbolo) {
        return InstrumentoResponse.de(servicio.buscar(simbolo));
    }

    /** 201 Created con el header Location: el instrumento ya quedó guardado cuando llega la respuesta. */
    @PostMapping
    public ResponseEntity<InstrumentoResponse> crear(@Valid @RequestBody CrearInstrumentoRequest request) {
        Instrumento instrumento = servicio.crear(request);
        return ResponseEntity.created(URI.create("/api/instrumentos/" + instrumento.getSimbolo()))
                .body(conPrecios(instrumento));
    }

    @PutMapping("/{simbolo}")
    public InstrumentoResponse editar(@PathVariable String simbolo, @Valid @RequestBody EditarInstrumentoRequest request) {
        return conPrecios(servicio.editar(simbolo, request));
    }

    /** Rechaza órdenes nuevas desde ya; las que están en el libro se conservan (RF-29). */
    @PostMapping("/{simbolo}/suspender")
    public InstrumentoResponse suspender(@PathVariable String simbolo) {
        return conPrecios(servicio.suspender(simbolo));
    }

    @PostMapping("/{simbolo}/reactivar")
    public InstrumentoResponse reactivar(@PathVariable String simbolo) {
        return conPrecios(servicio.reactivar(simbolo));
    }

    /** El instrumento recién cambiado, ya confirmado en la base, con sus precios del día como en las consultas. */
    private InstrumentoResponse conPrecios(Instrumento instrumento) {
        return InstrumentoResponse.de(servicio.buscar(instrumento.getSimbolo()));
    }
}
