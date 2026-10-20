package co.bolsasim.matching.motor;

import co.bolsasim.eventos.ComandoOrden;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Los mensajes que produjo cada uno de los últimos comandos procesados, para reconocer uno repetido (RNF-09).
 *
 * <p>Un comando puede llegar dos veces: el outbox de order-service entrega al menos una vez, y si una
 * transacción de Kafka se aborta, el mismo comando vuelve a entregarse aunque el libro en memoria ya cambió.
 * En los dos casos el motor no vuelve a tocar el libro: reenvía los mismos mensajes, con los mismos ids, y
 * quien los consume ignora los que ya tenía.
 *
 * <p>Se recuerdan los últimos N (los más viejos se olvidan): un repetido llega en milisegundos o segundos.
 * Viven en memoria, igual que los libros: al reiniciar el motor empiezan vacíos.
 */
class ComandosRecordados {

    private final Map<String, List<Mensaje>> recordados;

    ComandosRecordados(int maximo) {
        // accessOrder = false: se olvida el que se procesó primero
        this.recordados = new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<Mensaje>> masViejo) {
                return size() > maximo;
            }
        };
    }

    /** NUEVA y CANCELAR de la misma orden son comandos distintos: la clave lleva las dos cosas. */
    private static String clave(ComandoOrden comando) {
        return comando.accion() + "/" + comando.ordenId();
    }

    synchronized Optional<List<Mensaje>> buscar(ComandoOrden comando) {
        return Optional.ofNullable(recordados.get(clave(comando)));
    }

    synchronized void recordar(ComandoOrden comando, List<Mensaje> mensajes) {
        recordados.put(clave(comando), mensajes);
    }

    synchronized int cuantos() {
        return recordados.size();
    }
}
