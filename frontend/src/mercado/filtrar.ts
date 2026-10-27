import type { Instrumento } from '../api/tipos';

/** Sin tildes ni mayúsculas: "and" encuentra ANDX y "energia" encuentra "Energía". */
function normalizar(texto: string): string {
  return texto.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase();
}

/** HU-14: los instrumentos cuyo símbolo o nombre contiene el texto. Con el texto vacío, todos. */
export function filtrarInstrumentos(instrumentos: readonly Instrumento[], texto: string): Instrumento[] {
  const buscado = normalizar(texto.trim());
  if (buscado === '') {
    return [...instrumentos];
  }
  return instrumentos.filter(
    (instrumento) => normalizar(instrumento.simbolo).includes(buscado) || normalizar(instrumento.nombre).includes(buscado),
  );
}
