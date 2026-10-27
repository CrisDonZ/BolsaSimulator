import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { almacenTokens } from '../api/almacenTokens';
import { TEXTO_AVISO_LEGAL } from '../comun/AvisoLegal';
import {
  INSTRUMENTOS,
  json,
  perfil,
  problema,
  sesion,
  simularApi,
  sinContenido,
  tokenDePrueba,
  USUARIO_ID,
} from '../pruebas/apiSimulada';
import { renderizarApp } from '../pruebas/renderizar';
import { leerToken } from './jwt';
import { destinoSeguro } from './RutaProtegida';

/** La API de una cuenta que existe: login correcto, su perfil y los instrumentos. */
function apiConCuenta(extra = {}) {
  return simularApi({
    'POST /api/auth/login': () => json(sesion()),
    [`GET /api/usuarios/${USUARIO_ID}`]: () => json(perfil()),
    'GET /api/instrumentos': () => json(INSTRUMENTOS),
    ...extra,
  });
}

async function ingresar(usuario: ReturnType<typeof renderizarApp>['usuario'], contrasena = 'secreta123') {
  await usuario.type(screen.getByLabelText('Correo'), 'ana@correo.co');
  await usuario.type(screen.getByLabelText('Contraseña'), contrasena);
  await usuario.click(screen.getByRole('button', { name: 'Entrar' }));
}

describe('Iniciar sesión (HU-02)', () => {
  it('con credenciales correctas entra a Mercado y muestra su alias', async () => {
    const api = apiConCuenta();
    const { usuario } = renderizarApp('/ingresar');

    await ingresar(usuario);

    expect(await screen.findByRole('heading', { name: 'Mercado' })).toBeInTheDocument();
    expect(await screen.findByText('ana_inversora')).toBeInTheDocument();
    expect(api.a('/api/auth/login')[0]?.cuerpo).toEqual({ correo: 'ana@correo.co', contrasena: 'secreta123' });
  });

  it('con credenciales incorrectas dice "Correo o contraseña incorrectos" y borra la contraseña', async () => {
    const api = simularApi({
      'POST /api/auth/login': () => problema(401, 'Credenciales inválidas', 'Correo o contraseña incorrectos'),
    });
    const { usuario } = renderizarApp('/ingresar');

    await ingresar(usuario, 'equivocada');

    expect(await screen.findByRole('alert')).toHaveTextContent('Correo o contraseña incorrectos');
    expect(screen.getByLabelText('Contraseña')).toHaveValue('');
    expect(api.a('/api/auth/refresh')).toHaveLength(0);
  });

  it('una ruta protegida sin sesión lleva a Ingresar y, después de entrar, vuelve a ella', async () => {
    apiConCuenta();
    const { usuario } = renderizarApp('/mercado');

    expect(await screen.findByRole('heading', { name: 'Iniciar sesión' })).toBeInTheDocument();
    await ingresar(usuario);

    expect(await screen.findByRole('heading', { name: 'Mercado' })).toBeInTheDocument();
  });

  it('al recargar la página recupera la sesión con el token de refresco', async () => {
    sessionStorage.setItem('bolsasim.tokenRefresco', 'refresco-guardado');
    const api = apiConCuenta({
      'POST /api/auth/refresh': () =>
        json({ tokenAcceso: tokenDePrueba(USUARIO_ID), tipoToken: 'Bearer', accesoExpiraEn: '' }),
    });

    renderizarApp('/mercado');

    expect(await screen.findByRole('heading', { name: 'Mercado' })).toBeInTheDocument();
    expect(api.a('/api/auth/refresh')).toHaveLength(1);
  });

  it('si el token de refresco ya no sirve al recargar, pide iniciar sesión', async () => {
    sessionStorage.setItem('bolsasim.tokenRefresco', 'revocado');
    simularApi({ 'POST /api/auth/refresh': () => problema(401, 'No autorizado', 'Token de refresco inválido') });

    renderizarApp('/mercado');

    expect(await screen.findByRole('heading', { name: 'Iniciar sesión' })).toBeInTheDocument();
    expect(sessionStorage.getItem('bolsasim.tokenRefresco')).toBeNull();
  });
});

describe('Cerrar sesión (HU-02)', () => {
  it('revoca el token de refresco y vuelve a Ingresar', async () => {
    const api = apiConCuenta({ 'POST /api/auth/logout': () => sinContenido() });
    const { usuario } = renderizarApp('/ingresar');
    await ingresar(usuario);
    await screen.findByRole('heading', { name: 'Mercado' });

    await usuario.click(screen.getByRole('button', { name: 'Cerrar sesión' }));

    expect(await screen.findByRole('heading', { name: 'Iniciar sesión' })).toBeInTheDocument();
    expect(api.a('/api/auth/logout')[0]?.cuerpo).toEqual({ tokenRefresco: 'refresco-de-prueba' });
    expect(almacenTokens.acceso()).toBeNull();
  });

  it('borra la sesión local aunque el servidor no responda', async () => {
    apiConCuenta({
      'POST /api/auth/logout': () => {
        throw new TypeError('Failed to fetch');
      },
    });
    const { usuario } = renderizarApp('/ingresar');
    await ingresar(usuario);
    await screen.findByRole('heading', { name: 'Mercado' });

    await usuario.click(screen.getByRole('button', { name: 'Cerrar sesión' }));

    expect(await screen.findByRole('heading', { name: 'Iniciar sesión' })).toBeInTheDocument();
    expect(almacenTokens.acceso()).toBeNull();
    expect(almacenTokens.refresco()).toBeNull();
  });
});

describe('Registrarme (HU-01)', () => {
  async function llenar(usuario: ReturnType<typeof renderizarApp>['usuario'], contrasena = 'secreta123') {
    await usuario.type(screen.getByLabelText('Nombre'), 'Ana');
    await usuario.type(screen.getByLabelText('Alias'), 'ana_inversora');
    await usuario.type(screen.getByLabelText('Correo'), 'ana@correo.co');
    await usuario.type(screen.getByLabelText('Contraseña'), contrasena);
  }

  it('sin aceptar la política de datos el botón Registrarme queda desactivado', async () => {
    simularApi({});
    const { usuario } = renderizarApp('/registro');

    await llenar(usuario);

    expect(screen.getByRole('button', { name: 'Registrarme' })).toBeDisabled();
    await usuario.click(screen.getByLabelText(/Autorizo el tratamiento de mis datos/));
    expect(screen.getByRole('button', { name: 'Registrarme' })).toBeEnabled();
  });

  it('una contraseña de menos de 8 caracteres muestra el error mientras se escribe, sin enviar nada', async () => {
    const api = simularApi({});
    const { usuario } = renderizarApp('/registro');

    await usuario.type(screen.getByLabelText('Contraseña'), 'corta');

    expect(screen.getByText('La contraseña debe tener al menos 8 caracteres.')).toBeInTheDocument();
    expect(screen.getByLabelText('Contraseña')).toHaveAttribute('aria-invalid', 'true');
    expect(api.llamadas).toHaveLength(0);
  });

  it('con un correo que ya tiene cuenta muestra el mensaje del servidor', async () => {
    simularApi({ 'POST /api/usuarios': () => problema(409, 'Usuario duplicado', 'Ese correo ya tiene una cuenta.') });
    const { usuario } = renderizarApp('/registro');
    await llenar(usuario);
    await usuario.click(screen.getByLabelText(/Autorizo el tratamiento de mis datos/));

    await usuario.click(screen.getByRole('button', { name: 'Registrarme' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Ese correo ya tiene una cuenta.');
  });

  it('crea la cuenta con la autorización expresa, inicia sesión y entra a Mercado', async () => {
    const api = apiConCuenta({ 'POST /api/usuarios': () => json(perfil(), 201) });
    const { usuario } = renderizarApp('/registro');
    await llenar(usuario);
    await usuario.click(screen.getByLabelText(/Autorizo el tratamiento de mis datos/));

    await usuario.click(screen.getByRole('button', { name: 'Registrarme' }));

    expect(await screen.findByRole('heading', { name: 'Mercado' })).toBeInTheDocument();
    // Solo nombre, alias, correo y contraseña (RNF-30), y la autorización (RNF-28)
    expect(api.a('/api/usuarios')[0]?.cuerpo).toEqual({
      nombre: 'Ana',
      alias: 'ana_inversora',
      correo: 'ana@correo.co',
      contrasena: 'secreta123',
      aceptaPoliticaDatos: true,
    });
    expect(api.a('/api/auth/login')).toHaveLength(1);
  });
});

describe('Aviso legal (RNF-29)', () => {
  it.each(['/ingresar', '/registro', '/no-existe'])('aparece en %s', async (ruta) => {
    simularApi({});
    renderizarApp(ruta);

    expect(await screen.findByText(TEXTO_AVISO_LEGAL)).toBeInTheDocument();
  });

  it('aparece en Mercado', async () => {
    almacenTokens.guardar(tokenDePrueba(USUARIO_ID), 'refresco');
    simularApi({
      [`GET /api/usuarios/${USUARIO_ID}`]: () => json(perfil()),
      'GET /api/instrumentos': () => json(INSTRUMENTOS),
    });
    renderizarApp('/mercado');

    await screen.findByRole('heading', { name: 'Mercado' });
    expect(screen.getByText(TEXTO_AVISO_LEGAL)).toBeInTheDocument();
  });
});

describe('leerToken y destinoSeguro', () => {
  it('lee el usuario, el rol y el vencimiento del JWT, y rechaza lo que no es un JWT', () => {
    const datos = leerToken(tokenDePrueba(USUARIO_ID, 'ADMIN'));

    expect(datos?.usuarioId).toBe(USUARIO_ID);
    expect(datos?.rol).toBe('ADMIN');
    expect(datos?.expiraEn).toBeGreaterThan(Date.now());
    expect(leerToken('no-es-un-token')).toBeNull();
    expect(leerToken('a.b.c')).toBeNull();
  });

  it('después de entrar solo vuelve a rutas internas', () => {
    expect(destinoSeguro({ desde: '/mercado?x=1' })).toBe('/mercado?x=1');
    expect(destinoSeguro({ desde: 'https://otro.sitio' })).toBe('/mercado');
    expect(destinoSeguro({ desde: '//otro.sitio' })).toBe('/mercado');
    expect(destinoSeguro({ desde: '/\\otro.sitio' })).toBe('/mercado');
    expect(destinoSeguro(null)).toBe('/mercado');
  });
});

describe('sin errores en la consola', () => {
  it('iniciar sesión no imprime el token', async () => {
    const espia = vi.spyOn(console, 'log');
    apiConCuenta();
    const { usuario } = renderizarApp('/ingresar');

    await ingresar(usuario);
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Mercado' })).toBeInTheDocument());

    expect(espia).not.toHaveBeenCalled();
  });
});
