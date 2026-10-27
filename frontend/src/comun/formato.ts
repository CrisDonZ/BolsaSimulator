// "always": en español, Intl no agrupa los números de 4 cifras (5000); en BolsaSim siempre se escribe 5.000
const pesos = new Intl.NumberFormat('es-CO', { useGrouping: 'always', maximumFractionDigits: 0 });

/** Un monto en COP como lo lee una persona en Colombia: 1.000.000, con punto de miles. */
export function formatoPesos(monto: number): string {
  return pesos.format(monto);
}
