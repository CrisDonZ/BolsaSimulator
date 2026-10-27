import { useId, type InputHTMLAttributes } from 'react';

interface Props extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> {
  etiqueta: string;
  /** El error del campo; null si no tiene. Se anuncia a los lectores de pantalla. */
  error?: string | null;
  ayuda?: string;
}

/** Un campo de formulario con su etiqueta, su ayuda y su error enlazados (RNF-41, accesibilidad). */
export function Campo({ etiqueta, error = null, ayuda, ...entrada }: Props) {
  const id = useId();
  const idAyuda = `${id}-ayuda`;
  const idError = `${id}-error`;
  const descritoPor = [ayuda === undefined ? null : idAyuda, error === null ? null : idError].filter(Boolean).join(' ');

  return (
    <div className="campo">
      <label htmlFor={id}>{etiqueta}</label>
      <input
        id={id}
        aria-invalid={error !== null}
        aria-describedby={descritoPor === '' ? undefined : descritoPor}
        {...entrada}
      />
      {ayuda !== undefined && (
        <p id={idAyuda} className="campo-ayuda">
          {ayuda}
        </p>
      )}
      <p id={idError} className="campo-error" aria-live="polite">
        {error}
      </p>
    </div>
  );
}
