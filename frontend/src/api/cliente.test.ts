import { describe, expect, it } from 'vitest';
import { json, problema, simularApi, tokenDePrueba, USUARIO_ID } from '../pruebas/apiSimulada';
import { almacenTokens } from './almacenTokens';
import { iniciarSesion } from './auth';
import { pedir } from './cliente';
import { SESION_VENCIDA, SIN_CONEXION } from './ErrorApi';

function todoLoGuardado(almacenamiento: Storage): string {
  let texto = '';
  for (let i = 0; i < almacenamiento.length; i++) {
    const clave = almacenamiento.key(i) ?? '';
    texto += `${clave}=${almacenamiento.getItem(clave) ?? ''};`;
  }
  return texto;
}

const VIEJO = tokenDePrueba(USUARIO_ID);
const NUEVO = tokenDePrueba(USUARIO_ID);

describe('cliente HTTP', () => {
  it('manda el token de acceso en el header Authorization', async () => {
    almacenTokens.guardar(VIEJO, 'refresco');
    const api = simularApi({ 'GET /api/instrumentos': () => json([]) });

    await pedir('/instrumentos');

    expect(api.llamadas[0]?.autorizacion).toBe(`Bearer ${VIEJO}`);
  });

  it('con un 401 renueva el token una sola vez aunque fallen dos peticiones a la vez, y las reintenta', async () => {
    almacenTokens.guardar(VIEJO, 'refresco');
    const api = simularApi({
      'GET /api/instrumentos': (llamada) =>
        llamada.autorizacion === `Bearer ${NUEVO}` ? json([]) : problema(401, 'No autorizado', 'Token vencido'),
      'GET /api/cuenta': (llamada) =>
        llamada.autorizacion === `Bearer ${NUEVO}` ? json({}) : problema(401, 'No autorizado', 'Token vencido'),
      'POST /api/auth/refresh': async () => {
        // Lento a propósito: las dos peticiones esperan la misma renovación
        await new Promise((listo) => setTimeout(listo, 20));
        return json({ tokenAcceso: NUEVO, tipoToken: 'Bearer', accesoExpiraEn: '2026-10-27T16:00:00Z' });
      },
    });

    await Promise.all([pedir('/instrumentos'), pedir('/cuenta')]);

    expect(api.a('/api/auth/refresh')).toHaveLength(1);
    expect(api.a('/api/auth/refresh')[0]?.cuerpo).toEqual({ tokenRefresco: 'refresco' });
    expect(api.a('/api/instrumentos').map((l) => l.autorizacion)).toEqual([`Bearer ${VIEJO}`, `Bearer ${NUEVO}`]);
    expect(api.a('/api/cuenta').map((l) => l.autorizacion)).toEqual([`Bearer ${VIEJO}`, `Bearer ${NUEVO}`]);
    expect(almacenTokens.acceso()).toBe(NUEVO);
  });

  it('si el refresco ya no sirve, cierra la sesión local y dice que terminó', async () => {
    almacenTokens.guardar(VIEJO, 'revocado');
    simularApi({
      'GET /api/instrumentos': () => problema(401, 'No autorizado', 'Token vencido'),
      'POST /api/auth/refresh': () => problema(401, 'No autorizado', 'Token de refresco inválido'),
    });

    await expect(pedir('/instrumentos')).rejects.toThrow(SESION_VENCIDA);

    expect(almacenTokens.acceso()).toBeNull();
    expect(almacenTokens.refresco()).toBeNull();
    expect(sessionStorage.length).toBe(0);
  });

  it('un login fallido no intenta renovar nada', async () => {
    const api = simularApi({
      'POST /api/auth/login': () => problema(401, 'Credenciales inválidas', 'Correo o contraseña incorrectos'),
    });

    await expect(iniciarSesion('ana@correo.co', 'equivocada')).rejects.toThrow('Correo o contraseña incorrectos');

    expect(api.a('/api/auth/refresh')).toHaveLength(0);
    expect(api.llamadas[0]?.autorizacion).toBeNull();
  });

  it('muestra el detail de los Problem Details tal como lo manda el servicio', async () => {
    simularApi({
      'POST /api/usuarios': () => problema(409, 'Usuario duplicado', 'Ese correo ya tiene una cuenta.'),
    });

    await expect(pedir('/usuarios', { metodo: 'POST', cuerpo: {}, autenticado: false })).rejects.toMatchObject({
      estado: 409,
      message: 'Ese correo ya tiene una cuenta.',
    });
  });

  it('un 500 no muestra detalles internos y sin red dice que no pudo conectar', async () => {
    simularApi({ 'GET /api/instrumentos': () => problema(500, 'Error', 'NullPointerException en ServicioX') });
    await expect(pedir('/instrumentos', { autenticado: false })).rejects.toThrow(/Algo salió mal/);

    simularApi({
      'GET /api/instrumentos': () => {
        throw new TypeError('Failed to fetch');
      },
    });
    await expect(pedir('/instrumentos', { autenticado: false })).rejects.toThrow(SIN_CONEXION);
  });

  it('el token de acceso nunca se guarda en el almacenamiento del navegador', async () => {
    simularApi({
      'POST /api/auth/login': () =>
        json({ tokenAcceso: VIEJO, tipoToken: 'Bearer', tokenRefresco: 'refresco', accesoExpiraEn: '', refrescoExpiraEn: '' }),
    });

    await iniciarSesion('ana@correo.co', 'secreta123');

    expect(almacenTokens.acceso()).toBe(VIEJO);
    expect(todoLoGuardado(sessionStorage)).not.toContain(VIEJO);
    expect(todoLoGuardado(localStorage)).not.toContain(VIEJO);
    expect(sessionStorage.getItem('bolsasim.tokenRefresco')).toBe('refresco');
  });
});
