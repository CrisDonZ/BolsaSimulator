package co.bolsasim.portfolio.emision;

/**
 * Una fila de la tabla EMISION: la oferta inicial de un símbolo (RF-59), que espera a la cuenta del creador de
 * mercado mientras está PENDIENTE.
 */
public record Emision(String simbolo, long accionesEmitidas, long precioInicial) {
}
