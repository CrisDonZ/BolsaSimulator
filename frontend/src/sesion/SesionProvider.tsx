import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { almacenTokens } from '../api/almacenTokens';
import * as auth from '../api/auth';
import { renovar } from '../api/cliente';
import type { RegistroUsuario } from '../api/tipos';
import { Contexto, type ContextoSesion, type Sesion } from './contexto';
import { leerToken } from './jwt';

/** La sesión que dicen los tokens guardados, sin llamar al servidor. */
function sesionDeLosTokens(): Sesion {
  const acceso = almacenTokens.acceso();
  const datos = acceso === null ? null : leerToken(acceso);
  if (datos !== null) {
    return { estado: 'activa', usuario: { id: datos.usuarioId, rol: datos.rol, alias: null } };
  }
  return { estado: almacenTokens.refresco() === null ? 'anonima' : 'cargando' };
}

/**
 * Guarda quién inició sesión y lo mantiene al día con los tokens: si el cliente HTTP renueva el token o cierra la
 * sesión porque el refresco ya no sirve, la app se entera sola.
 */
export function SesionProvider({ children }: { children: ReactNode }) {
  const [sesion, setSesion] = useState<Sesion>(sesionDeLosTokens);
  const [alias, setAlias] = useState<{ id: string; alias: string } | null>(null);

  useEffect(() => {
    const actualizar = () => setSesion(sesionDeLosTokens());
    const dejar = almacenTokens.suscribir(actualizar);
    // Al recargar la página solo queda el token de refresco: se pide uno de acceso nuevo
    if (almacenTokens.acceso() === null && almacenTokens.refresco() !== null) {
      // Si no se pudo (refresco vencido o sin red), se muestra Ingresar en vez de quedarse cargando
      renovar().catch(() => setSesion((actual) => (actual.estado === 'cargando' ? { estado: 'anonima' } : actual)));
    }
    return dejar;
  }, []);

  const usuarioId = sesion.estado === 'activa' ? sesion.usuario.id : null;
  useEffect(() => {
    if (usuarioId === null || alias?.id === usuarioId) {
      return;
    }
    let vigente = true;
    auth
      .perfil(usuarioId)
      .then((perfil) => vigente && setAlias({ id: usuarioId, alias: perfil.alias }))
      // Sin alias el encabezado igual funciona: no vale la pena interrumpir al usuario
      .catch(() => undefined);
    return () => {
      vigente = false;
    };
  }, [usuarioId, alias]);

  const iniciarSesion = useCallback((correo: string, contrasena: string) => auth.iniciarSesion(correo, contrasena), []);

  const registrarse = useCallback(async (datos: RegistroUsuario) => {
    await auth.registrar(datos);
    await auth.iniciarSesion(datos.correo, datos.contrasena);
  }, []);

  const cerrarSesion = useCallback(async () => {
    try {
      await auth.cerrarSesion();
    } finally {
      setAlias(null);
    }
  }, []);

  const valor = useMemo<ContextoSesion>(() => {
    const conAlias: Sesion =
      sesion.estado === 'activa' && alias?.id === sesion.usuario.id
        ? { estado: 'activa', usuario: { ...sesion.usuario, alias: alias.alias } }
        : sesion;
    return { sesion: conAlias, iniciarSesion, registrarse, cerrarSesion };
  }, [sesion, alias, iniciarSesion, registrarse, cerrarSesion]);

  return <Contexto value={valor}>{children}</Contexto>;
}
