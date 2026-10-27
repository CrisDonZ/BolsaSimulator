package co.bolsasim.matching.motor;

import static org.assertj.core.api.Assertions.assertThat;

import co.bolsasim.eventos.AccionComando;
import co.bolsasim.eventos.ComandoOrden;
import co.bolsasim.eventos.Lado;
import co.bolsasim.eventos.TipoOrden;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Prueba unitaria del motor: comandos repetidos (RNF-09), sin Kafka. */
class MotorEmparejamientoTest {

    private final MotorEmparejamiento motor = new MotorEmparejamiento(100);

    @Test
    void unComandoRepetidoNoTocaElLibroYDevuelveLosMismosMensajes() {
        ComandoOrden venta = venta("beto", 100);
        motor.procesar(venta);
        ComandoOrden compra = compra("ana", 40);

        MotorEmparejamiento.Procesado primeraVez = motor.procesar(compra);
        // El outbox entregó la compra otra vez
        MotorEmparejamiento.Procesado otraVez = motor.procesar(compra);

        assertThat(otraVez.repetido()).isTrue();
        assertThat(otraVez.mensajes()).isEqualTo(primeraVez.mensajes());
        assertThat(otraVez.ejecuciones()).isEmpty();
        // Sin el control, la venta habría perdido otras 40 acciones
        assertThat(motor.pendienteDe("ANDX", venta.ordenId())).isEqualTo(60);
    }

    @Test
    void unaOrdenNuevaRepetidaQueQuedoEnElLibroNoEntraDosVeces() {
        ComandoOrden compra = compra("ana", 100);

        motor.procesar(compra);
        motor.procesar(compra);

        // Una venta de 200 solo encuentra las 100 de la compra, no 200
        MotorEmparejamiento.Procesado venta = motor.procesar(venta("beto", 200));
        assertThat(venta.ejecuciones()).singleElement().satisfies(e -> assertThat(e.cantidad()).isEqualTo(100));
    }

    @Test
    void cancelarNoSeConfundeConLaOrdenNuevaDeLaMismaOrden() {
        ComandoOrden compra = compra("ana", 100);
        motor.procesar(compra);

        MotorEmparejamiento.Procesado cancelacion = motor.procesar(cancelar(compra));

        assertThat(cancelacion.repetido()).isFalse();
        assertThat(cancelacion.mensajes()).hasSize(1);
        assertThat(motor.pendienteDe("ANDX", compra.ordenId())).isZero();
    }

    @Test
    void recuerdaSoloLosUltimosComandos() {
        MotorEmparejamiento conPocaMemoria = new MotorEmparejamiento(2);
        ComandoOrden primero = compra("ana", 1);
        conPocaMemoria.procesar(primero);
        conPocaMemoria.procesar(compra("beto", 1));
        conPocaMemoria.procesar(compra("cris", 1));

        // El primero ya se olvidó: se procesaría como nuevo
        assertThat(conPocaMemoria.procesar(primero).repetido()).isFalse();
    }

    private static ComandoOrden compra(String usuario, int cantidad) {
        return ComandoOrden.nueva(usuario, "ANDX", Lado.COMPRA, TipoOrden.LIMITE, 5_000L, cantidad);
    }

    private static ComandoOrden venta(String usuario, int cantidad) {
        return ComandoOrden.nueva(usuario, "ANDX", Lado.VENTA, TipoOrden.LIMITE, 5_000L, cantidad);
    }

    private static ComandoOrden cancelar(ComandoOrden orden) {
        return new ComandoOrden(UUID.randomUUID(), AccionComando.CANCELAR, orden.ordenId(), orden.usuarioId(),
                orden.simbolo(), orden.lado(), orden.tipo(), orden.precioLimite(), orden.precioReferencia(), orden.cantidad(),
                Instant.now());
    }
}
