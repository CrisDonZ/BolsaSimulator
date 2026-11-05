// "always": en español, Intl no agrupa los números de 4 cifras (5000); en BolsaSim siempre se escribe 5.000
const pesos = new Intl.NumberFormat('es-CO', { useGrouping: 'always', maximumFractionDigits: 0 });
const pesosConSigno = new Intl.NumberFormat('es-CO', {
  useGrouping: 'always',
  maximumFractionDigits: 0,
  signDisplay: 'exceptZero',
});
const precios = new Intl.NumberFormat('es-CO', { useGrouping: 'always', maximumFractionDigits: 2 });
const porcentaje = new Intl.NumberFormat('es-CO', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
  signDisplay: 'exceptZero',
});

/** Un monto en COP como lo lee una persona en Colombia: 1.000.000, con punto de miles. */
export function formatoPesos(monto: number): string {
  return pesos.format(monto);
}

/** Una ganancia o pérdida en COP, con su signo: +100.000, -50.000 o 0. */
export function formatoPesosConSigno(monto: number): string {
  return pesosConSigno.format(monto);
}

/** Un precio que puede traer centavos, como el precio promedio: 5.500 o 5.433,33. */
export function formatoPrecio(precio: number): string {
  return precios.format(precio);
}

/** Un porcentaje que ya viene multiplicado por 100, con su signo: +9,09 %, -10,00 % o 0,00 %. */
export function formatoPorcentaje(valor: number): string {
  return `${porcentaje.format(valor)} %`;
}
