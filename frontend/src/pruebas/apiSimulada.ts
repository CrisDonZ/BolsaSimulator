import { vi } from 'vitest';

export interface Llamada {
  metodo: string;
  ruta: string;
  autorizacion: string | null;
  cuerpo: unknown;
}

type Manejador = (llamada: Llamada) => Response | Promise<Response>;

/**
 * Reemplaza fetch por un servidor de mentira: cada clave es "MÉTODO /api/ruta" y devuelve la respuesta. Una ruta sin
 * manejador responde 404, así una llamada inesperada se nota en la prueba. Guarda cada llamada para revisarla después.
 */
export function simularApi(manejadores: Record<string, Manejador>) {
  const llamadas: Llamada[] = [];
  const fetchSimulado = vi.fn(async (entrada: RequestInfo | URL, init?: RequestInit) => {
    const ruta = typeof entrada === 'string' ? entrada : entrada instanceof URL ? entrada.pathname : entrada.url;
    const cabeceras = new Headers(init?.headers);
    const llamada: Llamada = {
      metodo: init?.method ?? 'GET',
      ruta,
      autorizacion: cabeceras.get('Authorization'),
      cuerpo: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
    };
    llamadas.push(llamada);
    const manejador = manejadores[`${llamada.metodo} ${ruta}`];
    return manejador === undefined ? problema(404, 'No encontrado', `Sin manejador para ${llamada.metodo} ${ruta}`) : manejador(llamada);
  });
  vi.stubGlobal('fetch', fetchSimulado);
  return {
    llamadas,
    /** Las llamadas a una ruta, con cualquier método. */
    a(ruta: string): Llamada[] {
      return llamadas.filter((llamada) => llamada.ruta === ruta);
    },
  };
}

export function json(cuerpo: unknown, estado = 200): Response {
  return new Response(JSON.stringify(cuerpo), { status: estado, headers: { 'Content-Type': 'application/json' } });
}

export function problema(estado: number, title: string, detail: string): Response {
  return new Response(JSON.stringify({ type: 'about:blank', title, status: estado, detail }), {
    status: estado,
    headers: { 'Content-Type': 'application/problem+json' },
  });
}

export function sinContenido(): Response {
  return new Response(null, { status: 204 });
}

/** Un JWT con la forma de los de auth-service. La firma no importa: el navegador no la verifica. */
export function tokenDePrueba(usuarioId: string, rol = 'INVERSIONISTA', minutos = 15): string {
  const base64url = (texto: string) => btoa(texto).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  const exp = Math.floor(Date.now() / 1000) + minutos * 60;
  return [
    base64url(JSON.stringify({ alg: 'RS256' })),
    base64url(JSON.stringify({ iss: 'bolsasim-auth', sub: usuarioId, rol, exp, jti: crypto.randomUUID() })),
    'firma',
  ].join('.');
}

export const USUARIO_ID = '7b3c1e9a-2d4f-4a6b-8c1d-0e9f8a7b6c5d';

export function sesion(tokenAcceso = tokenDePrueba(USUARIO_ID), tokenRefresco = 'refresco-de-prueba') {
  return {
    tokenAcceso,
    tipoToken: 'Bearer',
    accesoExpiraEn: new Date(Date.now() + 900_000).toISOString(),
    tokenRefresco,
    refrescoExpiraEn: new Date(Date.now() + 7 * 86_400_000).toISOString(),
  };
}

export function perfil(alias = 'ana_inversora') {
  return {
    id: USUARIO_ID,
    nombre: 'Ana',
    alias,
    correo: 'ana@correo.co',
    rol: 'INVERSIONISTA',
    estado: 'ACTIVO',
    creadoEn: '2026-10-20T15:00:00Z',
  };
}

export const INSTRUMENTOS = [
  instrumento('ANDX', 'Andes Energía', 'Energía', 5_000, 'ACTIVO'),
  instrumento('CAFE', 'Café de Colombia', 'Consumo', 12_500, 'ACTIVO'),
  instrumento('TAYR', 'Tayrona Telecom', 'Telecomunicaciones', 3_200, 'SUSPENDIDO'),
];

function instrumento(simbolo: string, nombre: string, sector: string, precio: number, estado: string) {
  return {
    simbolo,
    nombre,
    sector,
    precioInicial: precio,
    tick: 10,
    accionesEmitidas: 1_000_000,
    estado,
    creadoEn: '2026-10-01T00:00:00Z',
  };
}
