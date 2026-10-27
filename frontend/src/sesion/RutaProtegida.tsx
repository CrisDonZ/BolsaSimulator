import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';
import { Cargando } from '../comun/Cargando';
import { useSesion } from './contexto';

/**
 * Sin sesión, lleva a Ingresar y recuerda a dónde iba, para volver ahí después de entrar. Esto solo decide qué
 * pantalla se muestra: los datos los protege el backend, que exige el token en cada petición.
 */
export function RutaProtegida({ children }: { children: ReactNode }) {
  const { sesion } = useSesion();
  const ubicacion = useLocation();

  if (sesion.estado === 'cargando') {
    return <Cargando texto="Recuperando tu sesión..." />;
  }
  if (sesion.estado === 'anonima') {
    return <Navigate to="/ingresar" replace state={{ desde: ubicacion.pathname + ubicacion.search }} />;
  }
  return children;
}

/**
 * La ruta a la que se vuelve después de entrar. Solo rutas internas de la app: una dirección externa o "//otro.sitio"
 * llevaría al usuario fuera de BolsaSim justo después de escribir su contraseña.
 */
export function destinoSeguro(estado: unknown): string {
  const desde = typeof estado === 'object' && estado !== null ? (estado as { desde?: unknown }).desde : undefined;
  if (typeof desde === 'string' && desde.startsWith('/') && !desde.startsWith('//') && !desde.startsWith('/\\')) {
    return desde;
  }
  return '/mercado';
}
