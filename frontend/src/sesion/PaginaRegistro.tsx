import { useState, type FormEvent } from 'react';
import { Link, Navigate, useNavigate } from 'react-router';
import { mensajeDe } from '../api/ErrorApi';
import { Campo } from '../comun/Campo';
import { useSesion } from './contexto';
import { validar } from './validaciones';

type CampoRegistro = 'nombre' | 'alias' | 'correo' | 'contrasena';

const VACIO: Record<CampoRegistro, string> = { nombre: '', alias: '', correo: '', contrasena: '' };

/**
 * HU-01. Solo pide nombre, alias, correo y contraseña (RNF-30) y la autorización expresa del tratamiento de datos
 * (RNF-28): sin ella, Registrarme queda desactivado. Al crear la cuenta inicia sesión y entra a Mercado.
 */
export function PaginaRegistro() {
  const { sesion, registrarse } = useSesion();
  const navegar = useNavigate();
  const [valores, setValores] = useState(VACIO);
  const [tocados, setTocados] = useState<Partial<Record<CampoRegistro, boolean>>>({});
  const [acepta, setAcepta] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [enviando, setEnviando] = useState(false);

  if (sesion.estado === 'activa' && !enviando) {
    return <Navigate to="/mercado" replace />;
  }

  const errores: Record<CampoRegistro, string | null> = {
    nombre: validar.nombre(valores.nombre),
    alias: validar.alias(valores.alias),
    correo: validar.correo(valores.correo.trim()),
    contrasena: validar.contrasena(valores.contrasena),
  };
  const hayErrores = Object.values(errores).some((e) => e !== null);

  /** El error se muestra cuando el campo ya se tocó; la contraseña, mientras se escribe. */
  function errorVisible(campo: CampoRegistro): string | null {
    const visible = tocados[campo] === true || (campo === 'contrasena' && valores.contrasena !== '');
    return visible ? errores[campo] : null;
  }

  function cambiar(campo: CampoRegistro, valor: string) {
    setValores((actuales) => ({ ...actuales, [campo]: valor }));
  }

  function tocar(campo: CampoRegistro) {
    setTocados((actuales) => ({ ...actuales, [campo]: true }));
  }

  async function enviar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault();
    setTocados({ nombre: true, alias: true, correo: true, contrasena: true });
    if (hayErrores || !acepta) {
      return;
    }
    setError(null);
    setEnviando(true);
    try {
      await registrarse({
        nombre: valores.nombre.trim(),
        alias: valores.alias,
        correo: valores.correo.trim(),
        contrasena: valores.contrasena,
        aceptaPoliticaDatos: true,
      });
      navegar('/mercado', { replace: true });
    } catch (e) {
      // Por ejemplo "Ese correo ya tiene una cuenta.", tal como lo dice auth-service
      setError(mensajeDe(e));
      setEnviando(false);
    }
  }

  return (
    <section className="tarjeta" aria-labelledby="titulo-registro">
      <h1 id="titulo-registro">Crear tu cuenta</h1>
      <p>Empiezas con 10.000.000 COP virtuales para practicar.</p>
      <form onSubmit={enviar} noValidate>
        <Campo
          etiqueta="Nombre"
          autoComplete="name"
          value={valores.nombre}
          onChange={(e) => cambiar('nombre', e.target.value)}
          onBlur={() => tocar('nombre')}
          error={errorVisible('nombre')}
          maxLength={100}
          required
        />
        <Campo
          etiqueta="Alias"
          autoComplete="username"
          ayuda="Así te verán los demás en el ranking. De 3 a 30 letras, números o _."
          value={valores.alias}
          onChange={(e) => cambiar('alias', e.target.value)}
          onBlur={() => tocar('alias')}
          error={errorVisible('alias')}
          maxLength={30}
          required
        />
        <Campo
          etiqueta="Correo"
          type="email"
          autoComplete="email"
          value={valores.correo}
          onChange={(e) => cambiar('correo', e.target.value)}
          onBlur={() => tocar('correo')}
          error={errorVisible('correo')}
          maxLength={150}
          required
        />
        <Campo
          etiqueta="Contraseña"
          type="password"
          autoComplete="new-password"
          ayuda="De 8 a 72 caracteres."
          value={valores.contrasena}
          onChange={(e) => cambiar('contrasena', e.target.value)}
          onBlur={() => tocar('contrasena')}
          error={errorVisible('contrasena')}
          maxLength={72}
          required
        />
        <div className="casilla">
          <input
            id="acepta-politica"
            type="checkbox"
            checked={acepta}
            onChange={(e) => setAcepta(e.target.checked)}
          />
          <label htmlFor="acepta-politica">
            Autorizo el tratamiento de mis datos personales (nombre, alias y correo) para usar BolsaSim, según la Ley
            1581 de 2012.
          </label>
        </div>
        {error !== null && (
          <p className="alerta" role="alert">
            {error}
          </p>
        )}
        <button type="submit" disabled={!acepta || enviando}>
          {enviando ? 'Creando tu cuenta...' : 'Registrarme'}
        </button>
      </form>
      <p>
        ¿Ya tienes cuenta? <Link to="/ingresar">Inicia sesión</Link>
      </p>
    </section>
  );
}
