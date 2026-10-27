import { Link, Outlet, useNavigate } from 'react-router';
import { useState } from 'react';
import type { Rol } from '../api/tipos';
import { useSesion } from '../sesion/contexto';
import { AvisoLegal } from './AvisoLegal';

const NOMBRE_ROL: Record<Rol, string> = {
  INVERSIONISTA: 'Inversionista',
  ADMIN: 'Administrador',
  BOT: 'Bot',
};

/** Encabezado, contenido y aviso legal: todas las pantallas pasan por aquí, así ninguna queda sin el aviso. */
export function Plantilla() {
  const { sesion, cerrarSesion } = useSesion();
  const navegar = useNavigate();
  const [saliendo, setSaliendo] = useState(false);

  async function salir() {
    setSaliendo(true);
    try {
      await cerrarSesion();
    } catch {
      // La sesión local ya se borró aunque el servidor no respondiera
    } finally {
      setSaliendo(false);
      navegar('/ingresar', { replace: true });
    }
  }

  return (
    <div className="plantilla">
      <a className="saltar" href="#contenido">
        Ir al contenido
      </a>
      <header className="encabezado">
        <Link to="/mercado" className="marca">
          BolsaSim
        </Link>
        {sesion.estado === 'activa' && (
          <div className="encabezado-usuario">
            <span>
              {sesion.usuario.alias ?? 'Tu cuenta'}{' '}
              <span className="rol">({NOMBRE_ROL[sesion.usuario.rol]})</span>
            </span>
            <button type="button" className="boton-secundario" onClick={salir} disabled={saliendo}>
              {saliendo ? 'Cerrando...' : 'Cerrar sesión'}
            </button>
          </div>
        )}
      </header>
      <main id="contenido" className="contenido">
        <Outlet />
      </main>
      <AvisoLegal />
    </div>
  );
}
