import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';
import { App } from './App';
import { SesionProvider } from './sesion/SesionProvider';
import './estilos.css';

const raiz = document.getElementById('raiz');
if (raiz === null) {
  throw new Error('Falta el elemento #raiz en index.html');
}

createRoot(raiz).render(
  <StrictMode>
    <SesionProvider>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </SesionProvider>
  </StrictMode>,
);
