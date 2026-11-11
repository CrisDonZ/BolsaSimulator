package co.bolsasim.marketdata.servicio;

import co.bolsasim.marketdata.instrumento.Instrumento;
import co.bolsasim.marketdata.instrumento.RepositorioInstrumentos;
import co.bolsasim.marketdata.operacion.Cotizacion;
import co.bolsasim.marketdata.operacion.RepositorioOperaciones;
import co.bolsasim.marketdata.web.CrearInstrumentoRequest;
import co.bolsasim.marketdata.web.EditarInstrumentoRequest;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.function.BiPredicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reglas del catálogo (HU-22, RF-54): crear, editar, suspender y reactivar instrumentos. Cada cambio se
 * publica en instruments.events para que order-service (y después el motor y los bots) se enteren sin
 * reiniciar nada (RNF-20). Quién puede hacer cada cosa lo decide la cadena de seguridad, no este servicio.
 *
 * <p>Las consultas devuelven cada instrumento con su último precio y su variación del día (HU-14, RF-11, RF-40),
 * calculados en cada consulta a partir de OPERACION.
 */
@Service
public class ServicioInstrumentos {

    /**
     * "El día" de la variación es el día calendario en Colombia. Cuando el mercado abra y cierre (HU-23) pasa a
     * ser la sesión: la apertura será el precio con el que cerró la sesión anterior.
     */
    static final ZoneId ZONA = ZoneId.of("America/Bogota");

    private final RepositorioInstrumentos repositorio;
    private final PublicadorInstrumentos publicador;
    private final RepositorioOperaciones operaciones;

    public ServicioInstrumentos(RepositorioInstrumentos repositorio, PublicadorInstrumentos publicador,
                                RepositorioOperaciones operaciones) {
        this.repositorio = repositorio;
        this.publicador = publicador;
        this.operaciones = operaciones;
    }

    @Transactional
    public Instrumento crear(CrearInstrumentoRequest request) {
        String simbolo = normalizar(request.simbolo());
        if (request.precioInicial() % request.tick() != 0) {
            // El precio inicial también es un precio: con tick 10 vale 5000 o 5010, pero no 5003
            throw new InstrumentoInvalidoException("precioInicial debe ser múltiplo del tick (" + request.tick() + ").");
        }
        if (repositorio.existsById(simbolo)) {
            throw new InstrumentoDuplicadoException();
        }

        // saveAndFlush: si dos administradores crean el mismo símbolo a la vez, la llave primaria falla
        // aquí mismo y el ManejadorErrores responde 409, sin llegar a publicar el evento
        Instrumento guardado = repositorio.saveAndFlush(new Instrumento(simbolo, request.nombre().trim(),
                request.sector().trim(), request.precioInicial(), request.tick(), request.accionesEmitidas(), ahora()));
        publicador.publicar(guardado.comoEvento());
        return guardado;
    }

    @Transactional
    public Instrumento editar(String simbolo, EditarInstrumentoRequest request) {
        return cambiar(simbolo, (instrumento, ahora) ->
                instrumento.editar(request.nombre().trim(), request.sector().trim(), ahora));
    }

    @Transactional
    public Instrumento suspender(String simbolo) {
        return cambiar(simbolo, Instrumento::suspender);
    }

    @Transactional
    public Instrumento reactivar(String simbolo) {
        return cambiar(simbolo, Instrumento::reactivar);
    }

    @Transactional(readOnly = true)
    public List<Cotizacion> listar() {
        return operaciones.cotizaciones(inicioDelDia(Instant.now()));
    }

    @Transactional(readOnly = true)
    public Cotizacion buscar(String simbolo) {
        String normalizado = normalizar(simbolo);
        return operaciones.cotizacion(normalizado, inicioDelDia(Instant.now()))
                .orElseThrow(() -> new InstrumentoNoEncontradoException(normalizado));
    }

    /** Las 00:00 en Colombia del día al que pertenece ese momento. */
    static Instant inicioDelDia(Instant momento) {
        return momento.atZone(ZONA).toLocalDate().atStartOfDay(ZONA).toInstant();
    }

    /** Bloquea el instrumento, aplica el cambio y, solo si algo cambió, avisa a los demás servicios. */
    private Instrumento cambiar(String simbolo, BiPredicate<Instrumento, Instant> cambio) {
        String normalizado = normalizar(simbolo);
        Instrumento instrumento = repositorio.bloquear(normalizado)
                .orElseThrow(() -> new InstrumentoNoEncontradoException(normalizado));
        if (cambio.test(instrumento, ahora())) {
            publicador.publicar(instrumento.comoEvento());
        }
        return instrumento;
    }

    private static String normalizar(String simbolo) {
        return simbolo.trim().toUpperCase(Locale.ROOT);
    }

    /** En microsegundos, como los guarda PostgreSQL: el evento y la base dicen exactamente la misma hora. */
    private static Instant ahora() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
