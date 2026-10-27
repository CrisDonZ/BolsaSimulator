import { createContext, useContext } from 'react';
import type { RegistroUsuario, Rol } from '../api/tipos';

export interface UsuarioSesion {
  id: string;
  rol: Rol;
  /** Llega después, con el perfil: null mientras tanto o si no se pudo leer. */
  alias: string | null;
}

export type Sesion =
  | { estado: 'cargando' }
  | { estado: 'anonima' }
  | { estado: 'activa'; usuario: UsuarioSesion };

export interface ContextoSesion {
  sesion: Sesion;
  iniciarSesion(correo: string, contrasena: string): Promise<void>;
  /** Crea la cuenta e inicia sesión con ella. */
  registrarse(datos: RegistroUsuario): Promise<void>;
  cerrarSesion(): Promise<void>;
}

export const Contexto = createContext<ContextoSesion | null>(null);

export function useSesion(): ContextoSesion {
  const contexto = useContext(Contexto);
  if (contexto === null) {
    throw new Error('useSesion se usa dentro de <SesionProvider>');
  }
  return contexto;
}
