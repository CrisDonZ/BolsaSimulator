package co.bolsasim.portfolio.web;

import co.bolsasim.portfolio.servicio.Portafolio;
import co.bolsasim.portfolio.servicio.PosicionValorada;
import java.math.BigDecimal;
import java.util.List;

/**
 * El portafolio (RF-32, RF-33), en COP enteros. valorTotal es saldoTotal + valorAcciones: todo lo que tiene el
 * usuario. Ni el id de la cuenta ni el del usuario salen en la respuesta.
 */
public record PortafolioResponse(
        long saldoDisponible,
        long saldoReservado,
        long saldoTotal,
        long valorAcciones,
        long valorTotal,
        List<PosicionResponse> posiciones) {

    public static PortafolioResponse de(Portafolio portafolio) {
        return new PortafolioResponse(portafolio.cuenta().saldoDisponible(), portafolio.cuenta().saldoReservado(),
                portafolio.cuenta().saldoTotal(), portafolio.valorAcciones(), portafolio.valorTotal(),
                portafolio.posiciones().stream().map(PosicionResponse::de).toList());
    }

    /**
     * @param cantidadReservada  de la cantidad, las bloqueadas en órdenes de venta abiertas
     * @param precioPromedio     precio promedio de compra, con 4 decimales
     * @param gananciaPorcentaje con 2 decimales; negativa si pierde
     */
    public record PosicionResponse(
            String simbolo,
            long cantidad,
            long cantidadReservada,
            BigDecimal precioPromedio,
            long precioActual,
            long costo,
            long valorActual,
            long ganancia,
            BigDecimal gananciaPorcentaje) {

        static PosicionResponse de(PosicionValorada posicion) {
            return new PosicionResponse(posicion.simbolo(), posicion.cantidad(), posicion.cantidadReservada(),
                    posicion.precioPromedio(), posicion.precioActual(), posicion.costo(), posicion.valorActual(),
                    posicion.ganancia(), posicion.gananciaPorcentaje());
        }
    }
}
