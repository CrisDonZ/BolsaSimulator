import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { App } from '../App';
import { SesionProvider } from '../sesion/SesionProvider';

/** La app completa, con sus rutas y su sesión, empezando en esa ruta. */
export function renderizarApp(ruta = '/') {
  const usuario = userEvent.setup();
  const resultado = render(
    <SesionProvider>
      <MemoryRouter initialEntries={[ruta]}>
        <App />
      </MemoryRouter>
    </SesionProvider>,
  );
  return { usuario, ...resultado };
}
