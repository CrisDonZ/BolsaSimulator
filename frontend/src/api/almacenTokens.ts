/**
 * Dónde viven los tokens de la sesión.
 *
 * - El de acceso (15 minutos) solo en memoria: nunca toca localStorage ni sessionStorage, así un script ajeno que
 *   lea el almacenamiento no se lo lleva. Al recargar la página se pierde y se pide otro con el de refresco.
 * - El de refresco en sessionStorage: sobrevive a recargar la página y se borra al cerrar la pestaña. Un ataque XSS sí
 *   podría leerlo; por eso la app no inserta HTML crudo y el build lleva una CSP estricta. El reemplazo definitivo es
 *   una cookie HttpOnly que ponga el backend (ver "Siguientes pasos" del README).
 *
 * El almacenamiento puede fallar (modo privado, cookies bloqueadas): cada acceso va en try/catch y la sesión sigue en
 * memoria, solo que no sobrevive a una recarga.
 */
const CLAVE_REFRESCO = 'bolsasim.tokenRefresco';

type Oyente = () => void;

let acceso: string | null = null;
let refrescoEnMemoria: string | null = null;
const oyentes = new Set<Oyente>();

function avisar(): void {
  oyentes.forEach((oyente) => oyente());
}

export const almacenTokens = {
  acceso(): string | null {
    return acceso;
  },

  refresco(): string | null {
    try {
      return sessionStorage.getItem(CLAVE_REFRESCO) ?? refrescoEnMemoria;
    } catch {
      return refrescoEnMemoria;
    }
  },

  /** Al iniciar sesión: guarda los dos. */
  guardar(tokenAcceso: string, tokenRefresco: string): void {
    acceso = tokenAcceso;
    refrescoEnMemoria = tokenRefresco;
    try {
      sessionStorage.setItem(CLAVE_REFRESCO, tokenRefresco);
    } catch {
      // Sigue en memoria
    }
    avisar();
  },

  /** Al renovar: solo cambia el de acceso (el de refresco no rota). */
  guardarAcceso(tokenAcceso: string): void {
    acceso = tokenAcceso;
    avisar();
  },

  /** Al cerrar sesión o cuando el refresco ya no sirve: no queda nada. */
  limpiar(): void {
    const habia = acceso !== null || this.refresco() !== null;
    acceso = null;
    refrescoEnMemoria = null;
    try {
      sessionStorage.removeItem(CLAVE_REFRESCO);
    } catch {
      // No había nada guardado
    }
    if (habia) {
      avisar();
    }
  },

  /** Avisa cada vez que cambian los tokens; devuelve la función para dejar de escuchar. */
  suscribir(oyente: Oyente): () => void {
    oyentes.add(oyente);
    return () => oyentes.delete(oyente);
  },
};
