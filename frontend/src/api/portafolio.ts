import { pedir } from './cliente';
import type { Portafolio } from './tipos';

export function consultarPortafolio(senal?: AbortSignal): Promise<Portafolio> {
  return pedir<Portafolio>('/portafolio', senal === undefined ? {} : { senal });
}
