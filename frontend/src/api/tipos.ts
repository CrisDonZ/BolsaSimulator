/**
 * Los cuerpos de la API tal como los mandan los servicios a través del gateway. Escritos a mano, iguales a los DTO del
 * backend: si un DTO cambia, cambia aquí.
 */

/** auth-service: RegistroUsuarioRequest. */
export interface RegistroUsuario {
  nombre: string;
  alias: string;
  correo: string;
  contrasena: string;
  aceptaPoliticaDatos: boolean;
}

export type Rol = 'INVERSIONISTA' | 'ADMIN' | 'BOT';

/** auth-service: UsuarioResponse. */
export interface Usuario {
  id: string;
  nombre: string;
  alias: string;
  correo: string;
  rol: Rol;
  estado: string;
  creadoEn: string;
}

/** auth-service: SesionResponse, la respuesta del login. */
export interface SesionRespuesta {
  tokenAcceso: string;
  tipoToken: string;
  accesoExpiraEn: string;
  tokenRefresco: string;
  refrescoExpiraEn: string;
}

/** auth-service: TokenAccesoResponse, la respuesta de /refresh. */
export interface TokenAccesoRespuesta {
  tokenAcceso: string;
  tipoToken: string;
  accesoExpiraEn: string;
}

export type EstadoInstrumento = 'ACTIVO' | 'SUSPENDIDO';

/**
 * market-data-service: InstrumentoResponse. Precios en COP enteros. La variación del día es ultimoPrecio menos
 * precioApertura (el último precio antes de empezar el día); variacionPorcentaje ya viene multiplicado por 100.
 */
export interface Instrumento {
  simbolo: string;
  nombre: string;
  sector: string;
  precioInicial: number;
  tick: number;
  accionesEmitidas: number;
  estado: EstadoInstrumento;
  creadoEn: string;
  ultimoPrecio: number;
  precioApertura: number;
  variacion: number;
  variacionPorcentaje: number;
}

/** Problem Details (RFC 9457), el formato de error de todos los servicios. */
export interface ProblemDetails {
  title?: string;
  detail?: string;
  status?: number;
}

/** portfolio-service: PortafolioResponse.PosicionResponse. Montos en COP enteros; precioPromedio, con hasta 4 decimales. */
export interface Posicion {
  simbolo: string;
  cantidad: number;
  cantidadReservada: number;
  precioPromedio: number;
  precioActual: number;
  costo: number;
  valorActual: number;
  ganancia: number;
  gananciaPorcentaje: number;
}

/** portfolio-service: PortafolioResponse. valorTotal es saldoTotal + valorAcciones. */
export interface Portafolio {
  saldoDisponible: number;
  saldoReservado: number;
  saldoTotal: number;
  valorAcciones: number;
  valorTotal: number;
  posiciones: Posicion[];
}
