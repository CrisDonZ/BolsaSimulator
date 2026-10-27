import type { ProblemDetails } from './tipos';

export const SIN_CONEXION = 'No pudimos conectar con BolsaSim. Revisa tu conexión e intenta de nuevo.';
export const SESION_VENCIDA = 'Tu sesión terminó. Inicia sesión de nuevo.';
const ERROR_INESPERADO = 'Algo salió mal de nuestro lado. Intenta de nuevo en unos segundos.';

/**
 * Un error de la API con el mensaje que se le muestra al usuario. Los servicios ya responden en español y dicen qué
 * hacer (RNF-44): se usa su "detail" tal cual, en vez de inventar otro mensaje.
 */
export class ErrorApi extends Error {
  readonly estado: number;

  constructor(estado: number, mensaje: string) {
    super(mensaje);
    this.name = 'ErrorApi';
    this.estado = estado;
  }

  /** estado 0: la petición ni siquiera llegó (sin red, gateway apagado). */
  static sinConexion(): ErrorApi {
    return new ErrorApi(0, SIN_CONEXION);
  }

  static sesionVencida(): ErrorApi {
    return new ErrorApi(401, SESION_VENCIDA);
  }

  static desde(estado: number, cuerpo: ProblemDetails | null): ErrorApi {
    if (estado >= 500) {
      // Un 500 puede traer detalles internos: no se muestran
      return new ErrorApi(estado, ERROR_INESPERADO);
    }
    return new ErrorApi(estado, cuerpo?.detail ?? cuerpo?.title ?? ERROR_INESPERADO);
  }
}

/** El mensaje para el usuario de cualquier error, sea o no de la API. */
export function mensajeDe(error: unknown): string {
  return error instanceof ErrorApi ? error.message : ERROR_INESPERADO;
}
