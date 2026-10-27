export function Cargando({ texto = 'Cargando...' }: { texto?: string }) {
  return (
    <p className="cargando" role="status">
      {texto}
    </p>
  );
}
