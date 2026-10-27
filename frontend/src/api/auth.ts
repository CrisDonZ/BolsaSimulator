import { almacenTokens } from './almacenTokens';
import { pedir } from './cliente';
import type { RegistroUsuario, SesionRespuesta, Usuario } from './tipos';

/** HU-01. Con un correo repetido responde 409 "Ese correo ya tiene una cuenta.". */
export function registrar(datos: RegistroUsuario): Promise<Usuario> {
  return pedir<Usuario>('/usuarios', { metodo: 'POST', cuerpo: datos, autenticado: false });
}

/** HU-02. Con credenciales malas responde 401 "Correo o contraseña incorrectos", sin decir cuál falló. */
export async function iniciarSesion(correo: string, contrasena: string): Promise<void> {
  const sesion = await pedir<SesionRespuesta>('/auth/login', {
    metodo: 'POST',
    cuerpo: { correo, contrasena },
    autenticado: false,
  });
  almacenTokens.guardar(sesion.tokenAcceso, sesion.tokenRefresco);
}

/**
 * Revoca el token de refresco en auth-service y borra todo lo local. Lo local se borra aunque la llamada falle (sin
 * red, token vencido): cerrar sesión en este navegador nunca depende del servidor.
 */
export async function cerrarSesion(): Promise<void> {
  const refresco = almacenTokens.refresco();
  try {
    if (refresco !== null) {
      await pedir<undefined>('/auth/logout', { metodo: 'POST', cuerpo: { tokenRefresco: refresco } });
    }
  } finally {
    almacenTokens.limpiar();
  }
}

/** El perfil propio: auth-service solo deja ver el del usuario del token. */
export function perfil(usuarioId: string): Promise<Usuario> {
  return pedir<Usuario>(`/usuarios/${encodeURIComponent(usuarioId)}`);
}
