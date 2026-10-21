package co.bolsasim.order.servicio;

import co.bolsasim.eventos.EstadoOrden;

/** La orden no se puede cancelar: todavía no llega al libro, o ya terminó (RF-19). */
public class OrdenNoCancelableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private OrdenNoCancelableException(String mensaje) {
        super(mensaje);
    }

    public static OrdenNoCancelableException pendiente() {
        return new OrdenNoCancelableException(
                "La orden todavía se está procesando; intenta cancelarla en unos segundos.");
    }

    public static OrdenNoCancelableException terminada(EstadoOrden estado) {
        return new OrdenNoCancelableException("La orden ya terminó (" + estado + "): no se puede cancelar.");
    }
}
