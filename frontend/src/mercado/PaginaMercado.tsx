import { useEffect, useState } from 'react';
import { mensajeDe } from '../api/ErrorApi';
import { listarInstrumentos } from '../api/instrumentos';
import type { Instrumento } from '../api/tipos';
import { Cargando } from '../comun/Cargando';
import { formatoPesos, formatoPesosConSigno, formatoPorcentaje } from '../comun/formato';
import { filtrarInstrumentos } from './filtrar';

type Estado =
  | { tipo: 'cargando' }
  | { tipo: 'error'; mensaje: string }
  | { tipo: 'listo'; instrumentos: Instrumento[] };

/**
 * Mercado (HU-14): los instrumentos con su último precio, su variación del día y un buscador. Los precios son los del
 * momento en que se abre la página; la actualización en vivo llega con el WebSocket.
 */
export default function PaginaMercado() {
  const [estado, setEstado] = useState<Estado>({ tipo: 'cargando' });
  const [busqueda, setBusqueda] = useState('');
  const [intento, setIntento] = useState(0);

  useEffect(() => {
    const control = new AbortController();
    listarInstrumentos(control.signal)
      .then((instrumentos) => setEstado({ tipo: 'listo', instrumentos }))
      .catch((error: unknown) => {
        if (!control.signal.aborted) {
          setEstado({ tipo: 'error', mensaje: mensajeDe(error) });
        }
      });
    return () => control.abort();
  }, [intento]);

  function reintentar() {
    setEstado({ tipo: 'cargando' });
    setIntento((n) => n + 1);
  }

  return (
    <section aria-labelledby="titulo-mercado">
      <h1 id="titulo-mercado">Mercado</h1>
      <div className="campo buscador">
        <label htmlFor="buscar-instrumento">Buscar por símbolo o nombre</label>
        <input
          id="buscar-instrumento"
          type="search"
          value={busqueda}
          onChange={(e) => setBusqueda(e.target.value)}
          placeholder="Por ejemplo, AND"
          autoComplete="off"
        />
      </div>
      {estado.tipo === 'cargando' && <Cargando texto="Cargando instrumentos..." />}
      {estado.tipo === 'error' && (
        <div className="alerta" role="alert">
          <p>{estado.mensaje}</p>
          <button type="button" className="boton-secundario" onClick={reintentar}>
            Intentar de nuevo
          </button>
        </div>
      )}
      {estado.tipo === 'listo' && <TablaInstrumentos instrumentos={filtrarInstrumentos(estado.instrumentos, busqueda)} />}
    </section>
  );
}

function TablaInstrumentos({ instrumentos }: { instrumentos: Instrumento[] }) {
  if (instrumentos.length === 0) {
    return (
      <p role="status" className="vacio">
        Ningún instrumento coincide con tu búsqueda.
      </p>
    );
  }
  return (
    <div className="tabla-contenedor">
      <table className="tabla">
        <caption>
          {instrumentos.length === 1 ? '1 instrumento' : `${instrumentos.length} instrumentos`}. Precios en COP, con
          su variación del día.
        </caption>
        <thead>
          <tr>
            <th scope="col">Símbolo</th>
            <th scope="col">Nombre</th>
            <th scope="col" className="ocultable">
              Sector
            </th>
            <th scope="col" className="numero">
              Precio
            </th>
            <th scope="col">Estado</th>
          </tr>
        </thead>
        <tbody>
          {instrumentos.map((instrumento) => (
            <FilaInstrumento key={instrumento.simbolo} instrumento={instrumento} />
          ))}
        </tbody>
      </table>
    </div>
  );
}

function FilaInstrumento({ instrumento }: { instrumento: Instrumento }) {
  // RNF-42: si subió o bajó se lee en el signo (+ o -), no solo en el color
  const clase = instrumento.variacion > 0 ? 'ganancia' : instrumento.variacion < 0 ? 'perdida' : '';
  return (
    <tr>
      <th scope="row">{instrumento.simbolo}</th>
      <td>{instrumento.nombre}</td>
      <td className="ocultable">{instrumento.sector}</td>
      <td className="numero">
        {formatoPesos(instrumento.ultimoPrecio)}
        {/* Debajo del precio, en la misma celda: no agrega una columna y la tabla sigue cabiendo en 360 px (RNF-41).
            En un celular queda solo el porcentaje; la variación en COP se esconde con el sector */}
        <span className={`detalle ${clase}`}>
          <span className="ocultable">{formatoPesosConSigno(instrumento.variacion)} · </span>
          {formatoPorcentaje(instrumento.variacionPorcentaje)}
        </span>
      </td>
      <td>
        {/* RNF-42: el estado se lee en el texto, no solo en el color */}
        <span className={instrumento.estado === 'ACTIVO' ? 'etiqueta activo' : 'etiqueta suspendido'}>
          {instrumento.estado === 'ACTIVO' ? 'Activo' : 'Suspendido'}
        </span>
      </td>
    </tr>
  );
}
