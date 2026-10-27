import { useState, type FormEvent } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router';
import { mensajeDe } from '../api/ErrorApi';
import { Campo } from '../comun/Campo';
import { useSesion } from './contexto';
import { destinoSeguro } from './RutaProtegida';

/** HU-02: con credenciales correctas entra a Mercado, o a donde iba antes de que le pidieran iniciar sesión. */
export function PaginaIngresar() {
  const { sesion, iniciarSesion } = useSesion();
  const navegar = useNavigate();
  const ubicacion = useLocation();
  const destino = destinoSeguro(ubicacion.state);
  const [correo, setCorreo] = useState('');
  const [contrasena, setContrasena] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [enviando, setEnviando] = useState(false);

  if (sesion.estado === 'activa' && !enviando) {
    return <Navigate to={destino} replace />;
  }

  async function enviar(evento: FormEvent<HTMLFormElement>) {
    evento.preventDefault();
    setError(null);
    setEnviando(true);
    try {
      await iniciarSesion(correo.trim(), contrasena);
      navegar(destino, { replace: true });
    } catch (e) {
      // "Correo o contraseña incorrectos", tal como lo dice auth-service: sin decir cuál de los dos falló
      setError(mensajeDe(e));
    } finally {
      // La contraseña no se queda en memoria más de lo necesario
      setContrasena('');
      setEnviando(false);
    }
  }

  return (
    <section className="tarjeta" aria-labelledby="titulo-ingresar">
      <h1 id="titulo-ingresar">Iniciar sesión</h1>
      <form onSubmit={enviar} noValidate>
        <Campo
          etiqueta="Correo"
          type="email"
          autoComplete="email"
          value={correo}
          onChange={(e) => setCorreo(e.target.value)}
          required
        />
        <Campo
          etiqueta="Contraseña"
          type="password"
          autoComplete="current-password"
          value={contrasena}
          onChange={(e) => setContrasena(e.target.value)}
          required
        />
        {error !== null && (
          <p className="alerta" role="alert">
            {error}
          </p>
        )}
        <button type="submit" disabled={enviando || correo.trim() === '' || contrasena === ''}>
          {enviando ? 'Entrando...' : 'Entrar'}
        </button>
      </form>
      <p>
        ¿No tienes cuenta? <Link to="/registro">Regístrate</Link>
      </p>
    </section>
  );
}
