package co.bolsasim.matching.libro;

import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.Lado;
import co.bolsasim.eventos.TipoOrden;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Libro de órdenes de un símbolo. Java puro, sin Spring ni Kafka: se prueba con JUnit directamente.
 *
 * <p>Reglas de emparejamiento (prioridad precio-tiempo):
 * <ul>
 *   <li>Precio: la compra que más paga y la venta que menos pide se atienden primero.</li>
 *   <li>Tiempo: a igual precio, se atiende primero la orden que llegó antes.</li>
 *   <li>El precio de la ejecución es el de la orden que ya estaba esperando en el libro.</li>
 *   <li>LIMITE: lo que no se ejecuta queda esperando en el libro.</li>
 *   <li>MERCADO: se ejecuta contra lo disponible; lo que sobra se descarta, nunca queda en el libro.</li>
 * </ul>
 *
 * <p>No es seguro para varios hilos: quien lo use debe procesar un comando a la vez.
 */
public class LibroOrdenes {

    private final String simbolo;

    // Cada precio tiene una fila (ArrayDeque) con las órdenes en el orden en que llegaron.
    // Compras: el precio más alto primero. Ventas: el precio más bajo primero.
    private final TreeMap<Long, ArrayDeque<OrdenEnLibro>> compras = new TreeMap<>(Comparator.reverseOrder());
    private final TreeMap<Long, ArrayDeque<OrdenEnLibro>> ventas = new TreeMap<>();

    // Para encontrar una orden por su id al cancelarla sin recorrer todo el libro
    private final Map<UUID, OrdenEnLibro> porId = new HashMap<>();

    public LibroOrdenes(String simbolo) {
        this.simbolo = simbolo;
    }

    /** Aplica un comando y devuelve las ejecuciones que produjo (vacía si no hubo cruce). */
    public List<Ejecucion> procesar(ComandoOrden comando) {
        if (!simbolo.equals(comando.simbolo())) {
            throw new IllegalArgumentException(
                    "El libro de " + simbolo + " recibió una orden de " + comando.simbolo());
        }
        return switch (comando.accion()) {
            case NUEVA -> agregar(comando);
            case CANCELAR -> {
                cancelar(comando.ordenId());
                yield List.of();
            }
        };
    }

    public String simbolo() {
        return simbolo;
    }

    /** El precio más alto que alguien está dispuesto a pagar, si hay compras esperando. */
    public Optional<Long> mejorCompra() {
        return compras.isEmpty() ? Optional.empty() : Optional.of(compras.firstKey());
    }

    /** El precio más bajo al que alguien está dispuesto a vender, si hay ventas esperando. */
    public Optional<Long> mejorVenta() {
        return ventas.isEmpty() ? Optional.empty() : Optional.of(ventas.firstKey());
    }

    /** Acciones que le faltan por ejecutar a una orden; 0 si ya no está en el libro. */
    public int pendienteDe(UUID ordenId) {
        OrdenEnLibro orden = porId.get(ordenId);
        return orden == null ? 0 : orden.pendiente;
    }

    private List<Ejecucion> agregar(ComandoOrden comando) {
        TreeMap<Long, ArrayDeque<OrdenEnLibro>> contraparte = comando.lado() == Lado.COMPRA ? ventas : compras;
        List<Ejecucion> ejecuciones = new ArrayList<>();
        int pendiente = comando.cantidad();

        while (pendiente > 0 && !contraparte.isEmpty()) {
            long mejorPrecio = contraparte.firstKey();
            if (!cruza(comando, mejorPrecio)) {
                break;
            }

            ArrayDeque<OrdenEnLibro> fila = contraparte.get(mejorPrecio);
            OrdenEnLibro enEspera = fila.peekFirst();
            int cantidad = Math.min(pendiente, enEspera.pendiente);

            ejecuciones.add(ejecucion(comando, enEspera, mejorPrecio, cantidad));
            pendiente -= cantidad;
            enEspera.pendiente -= cantidad;

            if (enEspera.pendiente == 0) {
                fila.pollFirst();
                porId.remove(enEspera.ordenId);
                if (fila.isEmpty()) {
                    contraparte.remove(mejorPrecio);
                }
            }
        }

        if (pendiente > 0 && comando.tipo() == TipoOrden.LIMITE) {
            dejarEnLibro(comando, pendiente);
        }
        return ejecuciones;
    }

    /** ¿La orden nueva acepta el precio de la contraparte? Una orden a MERCADO acepta cualquiera. */
    private static boolean cruza(ComandoOrden comando, long precioContraparte) {
        if (comando.tipo() == TipoOrden.MERCADO) {
            return true;
        }
        return comando.lado() == Lado.COMPRA
                ? comando.precioLimite() >= precioContraparte
                : comando.precioLimite() <= precioContraparte;
    }

    private Ejecucion ejecucion(ComandoOrden nueva, OrdenEnLibro enEspera, long precio, int cantidad) {
        if (nueva.lado() == Lado.COMPRA) {
            return new Ejecucion(simbolo, precio, cantidad,
                    nueva.ordenId(), nueva.usuarioId(), enEspera.ordenId, enEspera.usuarioId);
        }
        return new Ejecucion(simbolo, precio, cantidad,
                enEspera.ordenId, enEspera.usuarioId, nueva.ordenId(), nueva.usuarioId());
    }

    private void dejarEnLibro(ComandoOrden comando, int pendiente) {
        var orden = new OrdenEnLibro(comando.ordenId(), comando.usuarioId(), comando.lado(),
                comando.precioLimite(), pendiente);
        ladoDe(orden.lado).computeIfAbsent(orden.precio, precio -> new ArrayDeque<>()).addLast(orden);
        porId.put(orden.ordenId, orden);
    }

    /** Retira una orden del libro. Si ya se ejecutó completa o no existe, no hace nada. */
    private void cancelar(UUID ordenId) {
        OrdenEnLibro orden = porId.remove(ordenId);
        if (orden == null) {
            return;
        }
        TreeMap<Long, ArrayDeque<OrdenEnLibro>> lado = ladoDe(orden.lado);
        ArrayDeque<OrdenEnLibro> fila = lado.get(orden.precio);
        fila.remove(orden);
        if (fila.isEmpty()) {
            lado.remove(orden.precio);
        }
    }

    private TreeMap<Long, ArrayDeque<OrdenEnLibro>> ladoDe(Lado lado) {
        return lado == Lado.COMPRA ? compras : ventas;
    }
}
