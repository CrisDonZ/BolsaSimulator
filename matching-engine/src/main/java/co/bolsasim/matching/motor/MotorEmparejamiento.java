package co.bolsasim.matching.motor;

import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.matching.libro.Ejecucion;
import co.bolsasim.matching.libro.LibroOrdenes;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Guarda un libro de órdenes por símbolo y le entrega a cada uno sus comandos.
 *
 * <p>Los libros viven en memoria: si el motor se reinicia, empiezan vacíos.
 */
@Component
public class MotorEmparejamiento {

    // ConcurrentHashMap porque el listener usa 3 hilos y cualquiera puede crear un libro nuevo
    private final Map<String, LibroOrdenes> libros = new ConcurrentHashMap<>();

    public List<Ejecucion> procesar(ComandoOrden comando) {
        LibroOrdenes libro = libroDe(comando.simbolo());
        // Las órdenes de un símbolo llegan por una sola partición, y cada partición la atiende un solo hilo.
        // El synchronized cubre el caso raro de un rebalanceo que pase la partición a otro hilo.
        synchronized (libro) {
            return libro.procesar(comando);
        }
    }

    /** Resumen para el log, por ejemplo: "ANDX | mejor compra 5000 | mejor venta 5100". */
    public String resumen(String simbolo) {
        LibroOrdenes libro = libroDe(simbolo);
        synchronized (libro) {
            return simbolo
                    + " | mejor compra " + libro.mejorCompra().map(String::valueOf).orElse("-")
                    + " | mejor venta " + libro.mejorVenta().map(String::valueOf).orElse("-");
        }
    }

    private LibroOrdenes libroDe(String simbolo) {
        return libros.computeIfAbsent(simbolo, LibroOrdenes::new);
    }
}
