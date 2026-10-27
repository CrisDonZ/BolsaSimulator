package co.bolsasim.matching.libro;

import java.util.List;
import java.util.UUID;

/**
 * Lo que pasó al aplicar un comando al libro: las ejecuciones, en orden, y cómo terminó.
 *
 * @param ejecuciones las operaciones que produjo (vacía si no hubo cruce)
 * @param desenlace   qué pasó con la orden del comando al final
 * @param cancelada   la orden retirada del libro, si el desenlace es CANCELADA; null en los demás casos
 */
public record ResultadoComando(List<Ejecucion> ejecuciones, Desenlace desenlace, OrdenCancelada cancelada) {

    public enum Desenlace {
        /** Orden LIMITE que quedó esperando en el libro, con o sin una parte ejecutada. */
        EN_LIBRO,
        /** Orden ejecutada completa. */
        EJECUTADA,
        /**
         * Orden a MERCADO a la que le sobró una parte (o toda) porque se acabó la contraparte: lo que sobra se cancela,
         * nunca queda en el libro.
         */
        SOBRANTE_CANCELADO,
        /**
         * Orden a MERCADO a la que le sobró una parte (o toda) porque la contraparte que queda está fuera de la banda de
         * ±10 % de su precio de referencia (RF-26): lo que sobra se cancela y esa contraparte sigue en el libro.
         */
        FUERA_DE_BANDA,
        /**
         * Orden a MERCADO que llegó sin precio de referencia (un comando de antes de HU-07): se cancela completa sin
         * tocar el libro, en vez de ejecutarse sin límite.
         */
        SIN_REFERENCIA,
        /** CANCELAR de una orden que estaba en el libro. */
        CANCELADA,
        /** CANCELAR de una orden que ya no estaba en el libro: nada cambió. */
        SIN_CAMBIOS
    }

    /**
     * La orden que un CANCELAR retiró del libro, con lo que necesita su evento.
     *
     * @param ejecutada acciones que se alcanzaron a ejecutar antes de cancelarla
     */
    public record OrdenCancelada(UUID ordenId, String usuarioId, int cantidad, int ejecutada) {
    }
}
