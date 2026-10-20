package co.bolsasim.order.orden;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Qué órdenes pide la lista: todas, las abiertas (ABIERTA y PARCIAL, como dice HU-09) o las de un estado.
 *
 * @param estado       el estado pedido; null si son todas o las abiertas
 * @param soloAbiertas true si son las abiertas
 */
public record FiltroEstado(EstadoOrden estado, boolean soloAbiertas) {

    public static final FiltroEstado TODAS = new FiltroEstado(null, false);
    public static final FiltroEstado ABIERTAS = new FiltroEstado(null, true);

    /** Los valores que acepta el parámetro estado, para decírselos a quien mande otro. */
    public static final String VALORES = "ABIERTAS, " + Arrays.stream(EstadoOrden.values())
            .map(Enum::name)
            .collect(Collectors.joining(", "));

    /**
     * @param valor el parámetro estado de la petición; vacío o null son todas
     * @throws IllegalArgumentException si no es ABIERTAS ni un estado
     */
    public static FiltroEstado de(String valor) {
        if (valor == null || valor.isBlank()) {
            return TODAS;
        }
        String normalizado = valor.trim().toUpperCase(Locale.ROOT);
        if (normalizado.equals("ABIERTAS")) {
            return ABIERTAS;
        }
        return new FiltroEstado(EstadoOrden.valueOf(normalizado), false);
    }
}
