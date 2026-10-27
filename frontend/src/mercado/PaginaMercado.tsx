import { useEffect, useState } from 'react';
import { mensajeDe } from '../api/ErrorApi';
import { listarInstrumentos } from '../api/instrumentos';
import type { Instrumento } from '../api/tipos';
import { Cargando } from '../comun/Cargando';
import { formatoPesos } from '../comun/formato';
import { filtrarInstrumentos } from './filtrar';

type Estado =
  | { tipo: 'cargando' }
  | { tipo: 'error'; mensaje: string }
  | { tipo: 'listo'; instrumentos: Instrumento[] };

/**
 * Mercado (inicio de HU-14): los instrumentos con su precio y un buscador. Por ahora el precio es el inicial; el último
 * precio, la variación del día y la actualización en vivo llegan con el resto de HU-14.
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
          {instrumentos.length === 1 ? '1 instrumento' : `${instrumentos.length} instrumentos`}. Precios en COP.
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
            <tr key={instrumento.simbolo}>
              <th scope="row">{instrumento.simbolo}</th>
              <td>{instrumento.nombre}</td>
              <td className="ocultable">{instrumento.sector}</td>
              <td className="numero">{formatoPesos(instrumento.precioInicial)}</td>
              <td>
                {/* RNF-42: el estado se lee en el texto, no solo en el color */}
                <span className={instrumento.estado === 'ACTIVO' ? 'etiqueta activo' : 'etiqueta suspendido'}>
                  {instrumento.estado === 'ACTIVO' ? 'Activo' : 'Suspendido'}
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
