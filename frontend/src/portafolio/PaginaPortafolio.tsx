import { useEffect, useState } from 'react';
import { mensajeDe } from '../api/ErrorApi';
import { consultarPortafolio } from '../api/portafolio';
import type { Portafolio, Posicion } from '../api/tipos';
import { Cargando } from '../comun/Cargando';
import { formatoPesos, formatoPesosConSigno, formatoPorcentaje, formatoPrecio } from '../comun/formato';

type Estado =
  | { tipo: 'cargando' }
  | { tipo: 'error'; mensaje: string }
  | { tipo: 'listo'; portafolio: Portafolio };

/**
 * Portafolio (HU-11): el valor total, el saldo y cada acción con su precio promedio, su valor actual y su ganancia o
 * pérdida. Los valores son los del momento de la consulta; la actualización en vivo llega con el WebSocket, y mientras
 * tanto el botón Actualizar vuelve a consultar.
 */
export default function PaginaPortafolio() {
  const [estado, setEstado] = useState<Estado>({ tipo: 'cargando' });
  const [intento, setIntento] = useState(0);

  useEffect(() => {
    const control = new AbortController();
    consultarPortafolio(control.signal)
      .then((portafolio) => setEstado({ tipo: 'listo', portafolio }))
      .catch((error: unknown) => {
        if (!control.signal.aborted) {
          setEstado({ tipo: 'error', mensaje: mensajeDe(error) });
        }
      });
    return () => control.abort();
  }, [intento]);

  function volverAConsultar() {
    setEstado({ tipo: 'cargando' });
    setIntento((n) => n + 1);
  }

  return (
    <section aria-labelledby="titulo-portafolio">
      <div className="titulo-con-accion">
        <h1 id="titulo-portafolio">Portafolio</h1>
        {estado.tipo === 'listo' && (
          <button type="button" className="boton-secundario" onClick={volverAConsultar}>
            Actualizar
          </button>
        )}
      </div>
      {estado.tipo === 'cargando' && <Cargando texto="Cargando tu portafolio..." />}
      {estado.tipo === 'error' && (
        <div className="alerta" role="alert">
          <p>{estado.mensaje}</p>
          <button type="button" className="boton-secundario" onClick={volverAConsultar}>
            Intentar de nuevo
          </button>
        </div>
      )}
      {estado.tipo === 'listo' && (
        <>
          <Resumen portafolio={estado.portafolio} />
          <TablaPosiciones posiciones={estado.portafolio.posiciones} />
        </>
      )}
    </section>
  );
}

function Resumen({ portafolio }: { portafolio: Portafolio }) {
  return (
    <dl className="resumen">
      <div className="resumen-dato principal">
        <dt>Valor total</dt>
        <dd>{formatoPesos(portafolio.valorTotal)}</dd>
      </div>
      <div className="resumen-dato">
        <dt>Acciones</dt>
        <dd>{formatoPesos(portafolio.valorAcciones)}</dd>
      </div>
      <div className="resumen-dato">
        <dt>Disponible</dt>
        <dd>{formatoPesos(portafolio.saldoDisponible)}</dd>
      </div>
      <div className="resumen-dato">
        <dt>Reservado</dt>
        <dd>{formatoPesos(portafolio.saldoReservado)}</dd>
      </div>
    </dl>
  );
}

function TablaPosiciones({ posiciones }: { posiciones: Posicion[] }) {
  if (posiciones.length === 0) {
    return (
      <p role="status" className="vacio">
        Todavía no tienes acciones. Cuando compres, aparecerán aquí con lo que valen.
      </p>
    );
  }
  return (
    <div className="tabla-contenedor">
      <table className="tabla">
        <caption>
          {posiciones.length === 1 ? '1 acción' : `${posiciones.length} acciones`}. Montos en COP, al último precio.
        </caption>
        <thead>
          <tr>
            <th scope="col">Símbolo</th>
            <th scope="col" className="numero">
              Cantidad
            </th>
            <th scope="col" className="numero">
              Precio promedio
            </th>
            <th scope="col" className="numero ocultable">
              Precio actual
            </th>
            <th scope="col" className="numero">
              Valor actual
            </th>
            <th scope="col" className="numero">
              Ganancia
            </th>
          </tr>
        </thead>
        <tbody>
          {posiciones.map((posicion) => (
            <FilaPosicion key={posicion.simbolo} posicion={posicion} />
          ))}
        </tbody>
      </table>
    </div>
  );
}

function FilaPosicion({ posicion }: { posicion: Posicion }) {
  // RNF-42: la ganancia o la pérdida se leen en el signo, no solo en el color
  const clase = posicion.ganancia > 0 ? 'ganancia' : posicion.ganancia < 0 ? 'perdida' : '';
  return (
    <tr>
      <th scope="row">{posicion.simbolo}</th>
      <td className="numero">
        {formatoPesos(posicion.cantidad)}
        {posicion.cantidadReservada > 0 && (
          <span className="detalle">{formatoPesos(posicion.cantidadReservada)} en venta</span>
        )}
      </td>
      <td className="numero">{formatoPrecio(posicion.precioPromedio)}</td>
      <td className="numero ocultable">{formatoPesos(posicion.precioActual)}</td>
      <td className="numero">{formatoPesos(posicion.valorActual)}</td>
      <td className={`numero ${clase}`}>
        {formatoPesosConSigno(posicion.ganancia)}
        <span className="detalle">{formatoPorcentaje(posicion.gananciaPorcentaje)}</span>
      </td>
    </tr>
  );
}
