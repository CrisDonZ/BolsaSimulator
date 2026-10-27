import { pedir } from './cliente';
import type { Instrumento } from './tipos';

export function listarInstrumentos(senal?: AbortSignal): Promise<Instrumento[]> {
  return pedir<Instrumento[]>('/instrumentos', senal === undefined ? {} : { senal });
}
