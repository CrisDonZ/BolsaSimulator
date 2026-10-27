import { almacenTokens } from './almacenTokens';
import { ErrorApi } from './ErrorApi';
import type { ProblemDetails, TokenAccesoRespuesta } from './tipos';

/**
 * El único lugar de la app que llama a fetch. Todo va al mismo origen (/api): en desarrollo Vite lo reenvía al
 * gateway, y en producción lo sirve el mismo dominio.
 *
 * Con un 401 en una ruta protegida renueva el token de acceso con el de refresco y reintenta una sola vez. Si varias
 * peticiones reciben 401 a la vez, comparten la misma renovación: sale un solo /refresh. Si la renovación falla, la
 * sesión se cierra en el cliente y quien llamó recibe "Tu sesión terminó".
 */
export interface Opciones {
  metodo?: 'GET' | 'POST' | 'PUT' | 'DELETE';
  cuerpo?: unknown;
  /** false en registro, login y refresco: no llevan token y un 401 no dispara una renovación. */
  autenticado?: boolean;
  senal?: AbortSignal;
}

const BASE = '/api';

let renovacionEnCurso: Promise<void> | null = null;

export async function pedir<T>(ruta: string, opciones: Opciones = {}): Promise<T> {
  const autenticado = opciones.autenticado ?? true;
  const tokenUsado = autenticado ? almacenTokens.acceso() : null;
  let respuesta = await enviar(ruta, opciones, tokenUsado);

  if (respuesta.status === 401 && autenticado) {
    // Si otra petición ya renovó el token mientras esta iba en camino, basta con reintentar con el nuevo
    if (almacenTokens.acceso() === tokenUsado) {
      await renovar();
    }
    respuesta = await enviar(ruta, opciones, almacenTokens.acceso());
    if (respuesta.status === 401) {
      almacenTokens.limpiar();
      throw ErrorApi.sesionVencida();
    }
  }
  return leer<T>(respuesta);
}

/**
 * Pide un token de acceso nuevo. Una sola renovación a la vez: las que lleguen mientras tanto esperan la misma.
 *
 * @throws ErrorApi con "Tu sesión terminó" si no hay token de refresco o ya no sirve (venció o se cerró la sesión)
 */
export function renovar(): Promise<void> {
  renovacionEnCurso ??= (async () => {
    try {
      const refresco = almacenTokens.refresco();
      if (refresco === null) {
        almacenTokens.limpiar();
        throw ErrorApi.sesionVencida();
      }
      const respuesta = await enviar('/auth/refresh', { metodo: 'POST', cuerpo: { tokenRefresco: refresco } }, null);
      if (!respuesta.ok) {
        almacenTokens.limpiar();
        throw respuesta.status === 401 ? ErrorApi.sesionVencida() : await errorDe(respuesta);
      }
      const nuevo = (await respuesta.json()) as TokenAccesoRespuesta;
      almacenTokens.guardarAcceso(nuevo.tokenAcceso);
    } finally {
      renovacionEnCurso = null;
    }
  })();
  return renovacionEnCurso;
}

async function enviar(ruta: string, opciones: Opciones, token: string | null): Promise<Response> {
  const cabeceras: Record<string, string> = { Accept: 'application/json' };
  if (opciones.cuerpo !== undefined) {
    cabeceras['Content-Type'] = 'application/json';
  }
  if (token !== null) {
    cabeceras.Authorization = `Bearer ${token}`;
  }
  const init: RequestInit = {
    method: opciones.metodo ?? 'GET',
    headers: cabeceras,
    // Los tokens van en el header, nunca en cookies del navegador ni en la URL
    credentials: 'omit',
  };
  if (opciones.cuerpo !== undefined) {
    init.body = JSON.stringify(opciones.cuerpo);
  }
  if (opciones.senal !== undefined) {
    init.signal = opciones.senal;
  }
  try {
    return await fetch(BASE + ruta, init);
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error;
    }
    throw ErrorApi.sinConexion();
  }
}

async function leer<T>(respuesta: Response): Promise<T> {
  if (!respuesta.ok) {
    throw await errorDe(respuesta);
  }
  if (respuesta.status === 204) {
    return undefined as T;
  }
  return (await respuesta.json()) as T;
}

async function errorDe(respuesta: Response): Promise<ErrorApi> {
  let cuerpo: ProblemDetails | null = null;
  try {
    cuerpo = (await respuesta.json()) as ProblemDetails;
  } catch {
    // Sin cuerpo JSON (por ejemplo, un 502 del proxy)
  }
  return ErrorApi.desde(respuesta.status, cuerpo);
}
