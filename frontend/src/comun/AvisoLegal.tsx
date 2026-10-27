export const TEXTO_AVISO_LEGAL =
  'Simulación educativa con dinero virtual: no es asesoría de inversión ni opera valores reales.';

/** RNF-29: en todas las pantallas, para que BolsaSim no se confunda con una actividad de intermediación de valores. */
export function AvisoLegal() {
  return (
    <footer className="aviso-legal">
      <p>{TEXTO_AVISO_LEGAL}</p>
    </footer>
  );
}
