import { lazy, Suspense } from 'react';
import { Link, Navigate, Route, Routes } from 'react-router';
import { Cargando } from './comun/Cargando';
import { Plantilla } from './comun/Plantilla';
import { PaginaIngresar } from './sesion/PaginaIngresar';
import { PaginaRegistro } from './sesion/PaginaRegistro';
import { RutaProtegida } from './sesion/RutaProtegida';

// Carga diferida: Mercado y lo que venga después (la ficha con el gráfico, el portafolio) no pesan en el login
const PaginaMercado = lazy(() => import('./mercado/PaginaMercado'));

/**
 * | Ruta        | Pantalla                  | Sesión                                     |
 * | /           | lleva a /mercado          |                                            |
 * | /ingresar   | Iniciar sesión (HU-02)    | con sesión, lleva a /mercado               |
 * | /registro   | Crear tu cuenta (HU-01)   | con sesión, lleva a /mercado               |
 * | /mercado    | Mercado (HU-14)           | protegida: sin sesión, lleva a /ingresar   |
 */
export function App() {
  return (
    <Routes>
      <Route element={<Plantilla />}>
        <Route index element={<Navigate to="/mercado" replace />} />
        <Route path="ingresar" element={<PaginaIngresar />} />
        <Route path="registro" element={<PaginaRegistro />} />
        <Route
          path="mercado"
          element={
            <RutaProtegida>
              <Suspense fallback={<Cargando />}>
                <PaginaMercado />
              </Suspense>
            </RutaProtegida>
          }
        />
        <Route path="*" element={<NoEncontrada />} />
      </Route>
    </Routes>
  );
}

function NoEncontrada() {
  return (
    <section className="tarjeta" aria-labelledby="titulo-no-encontrada">
      <h1 id="titulo-no-encontrada">Esta página no existe</h1>
      <p>
        Revisa la dirección o vuelve al <Link to="/mercado">mercado</Link>.
      </p>
    </section>
  );
}
