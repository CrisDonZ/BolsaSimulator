import type { Rol } from '../api/tipos';

export interface DatosToken {
  usuarioId: string;
  rol: Rol;
  /** Cuándo vence, en milisegundos desde 1970. */
  expiraEn: number;
}

const ROLES: readonly Rol[] = ['INVERSIONISTA', 'ADMIN', 'BOT'];

/**
 * Lee el usuario y el rol del token de acceso para mostrarlos. No verifica la firma: el navegador no tiene por qué
 * confiar en esto, y no lo hace. Quien valida el token en cada petición es el backend, y quien decide qué puede hacer
 * cada rol también.
 *
 * @return null si no tiene la forma de un JWT de auth-service
 */
export function leerToken(token: string): DatosToken | null {
  const partes = token.split('.');
  const carga = partes[1];
  if (partes.length !== 3 || carga === undefined) {
    return null;
  }
  try {
    const sinRelleno = carga.replace(/-/g, '+').replace(/_/g, '/');
    const base64 = sinRelleno.padEnd(Math.ceil(sinRelleno.length / 4) * 4, '=');
    const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
    const datos: unknown = JSON.parse(new TextDecoder().decode(bytes));
    if (typeof datos !== 'object' || datos === null) {
      return null;
    }
    const { sub, rol, exp } = datos as Record<string, unknown>;
    if (typeof sub !== 'string' || typeof exp !== 'number' || !ROLES.includes(rol as Rol)) {
      return null;
    }
    return { usuarioId: sub, rol: rol as Rol, expiraEn: exp * 1000 };
  } catch {
    return null;
  }
}
