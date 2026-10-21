package co.bolsasim.portfolio.servicio;

import co.bolsasim.portfolio.cuenta.Cuenta;
import co.bolsasim.portfolio.cuenta.RepositorioCuentas;
import co.bolsasim.portfolio.movimiento.Cursor;
import co.bolsasim.portfolio.movimiento.Movimiento;
import co.bolsasim.portfolio.movimiento.RegistroMovimientos;
import co.bolsasim.portfolio.movimiento.TipoMovimiento;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La cuenta de cada usuario (RF-07, RF-08) y su historial de movimientos (RF-09, RF-10).
 *
 * <p>La cuenta se abre cuando llega users.registered y, si todavía no existe, la primera vez que el usuario la
 * consulta: el token firmado por auth-service ya prueba que el usuario existe. Así reciben cuenta también los
 * usuarios que se registraron antes de portfolio-service. De las dos formas queda igual: el saldo inicial y su
 * movimiento SALDO_INICIAL, una sola vez.
 */
@Service
public class ServicioCuentas {

    private static final Logger log = LoggerFactory.getLogger(ServicioCuentas.class);

    /** Las fechas de los filtros son días en hora de Colombia, como los ve el usuario. */
    static final ZoneId ZONA = ZoneId.of("America/Bogota");

    public static final int TAMANO_POR_DEFECTO = 20;
    public static final int TAMANO_MAXIMO = 100;

    private static final String TIPOS = Arrays.stream(TipoMovimiento.values())
            .map(Enum::name)
            .collect(Collectors.joining(", "));

    private final RepositorioCuentas cuentas;
    private final RegistroMovimientos movimientos;
    private final long saldoInicial;

    public ServicioCuentas(RepositorioCuentas cuentas, RegistroMovimientos movimientos,
                           @Value("${bolsasim.cuenta.saldo-inicial}") long saldoInicial) {
        this.cuentas = cuentas;
        this.movimientos = movimientos;
        this.saldoInicial = saldoInicial;
    }

    /**
     * Abre la cuenta del usuario con el saldo inicial y su movimiento SALDO_INICIAL, si todavía no tiene una.
     *
     * @return la cuenta, nueva o la que ya tenía
     */
    @Transactional
    public Cuenta abrir(UUID usuarioId) {
        Instant ahora = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Optional<UUID> nueva = cuentas.crearSiNoExiste(usuarioId, saldoInicial, ahora);
        nueva.ifPresent(cuentaId -> {
            movimientos.registrar(cuentaId, TipoMovimiento.SALDO_INICIAL, saldoInicial, null, null, null, ahora);
            log.info("Cuenta del usuario {} abierta con {} COP", usuarioId, saldoInicial);
        });
        return cuentas.buscarPorUsuario(usuarioId).orElseThrow();
    }

    /**
     * Como {@link #abrir(UUID)}, y además guarda el rol del usuario (INVERSIONISTA o BOT) si la cuenta todavía no lo
     * tiene: una cuenta abierta con la primera orden no lo sabía.
     */
    @Transactional
    public Cuenta abrir(UUID usuarioId, String rol) {
        Cuenta cuenta = abrir(usuarioId);
        cuentas.completarRol(usuarioId, rol);
        return cuenta;
    }

    /**
     * La cuenta del usuario del token; si todavía no tiene (se registró antes de portfolio-service, o su evento
     * aún no llega), la abre. Un ADMIN no tiene cuenta: no opera.
     *
     * @param rol el claim "rol" del token
     */
    @Transactional
    public Cuenta consultar(UUID usuarioId, String rol) {
        if ("ADMIN".equals(rol)) {
            throw new SinCuentaException();
        }
        return cuentas.buscarPorUsuario(usuarioId).orElseGet(() -> abrir(usuarioId, rol));
    }

    /**
     * Una página de los movimientos del usuario, del más reciente al más viejo.
     *
     * @param tipo    uno de los tipos de movimiento, o null para todos
     * @param desde   día inicial (AAAA-MM-DD, hora de Colombia), incluido; null sin límite
     * @param hasta   día final, incluido completo; null sin límite
     * @param despues el cursor "siguiente" de la página anterior; null para la primera
     */
    @Transactional
    public PaginaMovimientos movimientos(UUID usuarioId, String rol, String tipo, String desde, String hasta,
                                         int tamano, String despues) {
        if (tamano < 1 || tamano > TAMANO_MAXIMO) {
            throw new ConsultaInvalidaException("tamano debe estar entre 1 y " + TAMANO_MAXIMO + ".");
        }
        TipoMovimiento filtro = tipo(tipo);
        LocalDate dia1 = dia("desde", desde);
        LocalDate dia2 = dia("hasta", hasta);
        if (dia1 != null && dia2 != null && dia1.isAfter(dia2)) {
            throw new ConsultaInvalidaException("desde no puede ser posterior a hasta.");
        }
        Cursor cursor = cursor(despues);
        Cuenta cuenta = consultar(usuarioId, rol);

        // Un movimiento de más dice si hay otra página, sin contar todos los movimientos
        List<Movimiento> filas = movimientos.buscar(cuenta.id(), filtro,
                dia1 == null ? null : dia1.atStartOfDay(ZONA).toInstant(),
                // "hasta el 20" incluye todo el 20: hasta el comienzo del 21, sin incluirlo
                dia2 == null ? null : dia2.plusDays(1).atStartOfDay(ZONA).toInstant(),
                cursor, tamano + 1);
        if (filas.size() <= tamano) {
            return new PaginaMovimientos(filas, null);
        }
        List<Movimiento> pagina = filas.subList(0, tamano);
        return new PaginaMovimientos(pagina, Cursor.despuesDe(pagina.getLast()));
    }

    private static TipoMovimiento tipo(String tipo) {
        if (tipo == null || tipo.isBlank()) {
            return null;
        }
        try {
            return TipoMovimiento.valueOf(tipo.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConsultaInvalidaException("tipo debe ser uno de: " + TIPOS + ".");
        }
    }

    private static LocalDate dia(String parametro, String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(valor.trim());
        } catch (DateTimeParseException e) {
            throw new ConsultaInvalidaException(parametro + " debe ser una fecha AAAA-MM-DD, por ejemplo 2026-10-20.");
        }
    }

    private static Cursor cursor(String despues) {
        if (despues == null || despues.isBlank()) {
            return null;
        }
        try {
            return Cursor.decodificar(despues.trim());
        } catch (IllegalArgumentException e) {
            throw new ConsultaInvalidaException(
                    "despues no es válido: usa el valor \"siguiente\" de la página anterior, sin cambiarlo.");
        }
    }
}
