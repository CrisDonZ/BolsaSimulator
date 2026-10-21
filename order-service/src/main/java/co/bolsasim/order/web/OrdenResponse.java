package co.bolsasim.order.web;

import co.bolsasim.eventos.Lado;
import co.bolsasim.eventos.TipoOrden;
import co.bolsasim.eventos.EstadoOrden;
import co.bolsasim.order.orden.Orden;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Una orden como la ve su dueño (RF-20). No lleva el usuario (siempre es quien pregunta) ni la versión del
 * bloqueo optimista, que es un detalle interno.
 *
 * @param precioPromedio          null mientras no se haya ejecutado nada
 * @param motivo                  por qué se rechazó o se canceló; null en los demás estados
 * @param cancelacionSolicitadaEn cuándo pediste cancelarla (HU-08); null si no lo pediste. La orden queda CANCELADA
 *                                cuando el motor lo confirma, o EJECUTADA si se ejecutó completa antes
 */
public record OrdenResponse(
        UUID id,
        String simbolo,
        Lado lado,
        TipoOrden tipo,
        Long precioLimite,
        int cantidad,
        int cantidadEjecutada,
        BigDecimal precioPromedio,
        EstadoOrden estado,
        String motivo,
        Instant creadaEn,
        Instant actualizadaEn,
        Instant cancelacionSolicitadaEn) {

    public static OrdenResponse de(Orden orden) {
        return new OrdenResponse(orden.getId(), orden.getSimbolo(), orden.getLado(), orden.getTipo(),
                orden.getPrecioLimite(), orden.getCantidad(), orden.getCantidadEjecutada(), orden.getPrecioPromedio(),
                orden.getEstado(), orden.getMotivo(), orden.getCreadaEn(), orden.getActualizadaEn(),
                orden.getCancelacionSolicitadaEn());
    }
}
