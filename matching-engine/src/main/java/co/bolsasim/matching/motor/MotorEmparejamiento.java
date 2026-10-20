package co.bolsasim.matching.motor;

import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.matching.libro.Ejecucion;
import co.bolsasim.matching.libro.LibroOrdenes;
import co.bolsasim.matching.libro.ResultadoComando;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Guarda un libro de órdenes por símbolo, le entrega a cada uno sus comandos y arma los mensajes que hay que
 * publicar.
 *
 * <p>Los libros viven en memoria: si el motor se reinicia, empiezan vacíos.
 */
@Component
public class MotorEmparejamiento {

    // ConcurrentHashMap porque el listener usa 3 hilos y cualquiera puede crear un libro nuevo
    private final Map<String, LibroOrdenes> libros = new ConcurrentHashMap<>();
    private final ComandosRecordados recordados;

    public MotorEmparejamiento(@Value("${bolsasim.motor.comandos-recordados:10000}") int comandosRecordados) {
        this.recordados = new ComandosRecordados(comandosRecordados);
    }

    /**
     * Aplica el comando y devuelve los mensajes para orders.events y trades.executed. Si el comando ya se había
     * procesado, no toca el libro y devuelve los mismos mensajes de la primera vez.
     */
    public Procesado procesar(ComandoOrden comando) {
        LibroOrdenes libro = libroDe(comando.simbolo());
        // Las órdenes de un símbolo llegan por una sola partición, y cada partición la atiende un solo hilo.
        // El synchronized cubre el caso raro de un rebalanceo que pase la partición a otro hilo.
        synchronized (libro) {
            Optional<List<Mensaje>> anteriores = recordados.buscar(comando);
            if (anteriores.isPresent()) {
                return new Procesado(anteriores.get(), List.of(), true);
            }
            ResultadoComando resultado = libro.procesar(comando);
            List<Mensaje> mensajes = EventosDelComando.de(comando, resultado, Instant.now());
            // Se recuerda antes de publicar: si la publicación falla y el comando vuelve, salen los mismos mensajes
            recordados.recordar(comando, mensajes);
            return new Procesado(mensajes, resultado.ejecuciones(), false);
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

    /** Acciones que le faltan a una orden del libro; 0 si ya no está. */
    public int pendienteDe(String simbolo, UUID ordenId) {
        LibroOrdenes libro = libroDe(simbolo);
        synchronized (libro) {
            return libro.pendienteDe(ordenId);
        }
    }

    private LibroOrdenes libroDe(String simbolo) {
        return libros.computeIfAbsent(simbolo, LibroOrdenes::new);
    }

    /**
     * Lo que dejó un comando.
     *
     * @param mensajes    lo que hay que publicar, en orden
     * @param ejecuciones las ejecuciones nuevas, para el log; vacía si el comando era repetido
     * @param repetido    true si el comando ya se había procesado y el libro no cambió
     */
    public record Procesado(List<Mensaje> mensajes, List<Ejecucion> ejecuciones, boolean repetido) {
    }
}
