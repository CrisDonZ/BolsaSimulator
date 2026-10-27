import { screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { almacenTokens } from '../api/almacenTokens';
import { INSTRUMENTOS, json, perfil, problema, simularApi, tokenDePrueba, USUARIO_ID } from '../pruebas/apiSimulada';
import { renderizarApp } from '../pruebas/renderizar';
import { filtrarInstrumentos } from './filtrar';
import type { Instrumento } from '../api/tipos';

describe('Mercado (HU-14)', () => {
  beforeEach(() => {
    almacenTokens.guardar(tokenDePrueba(USUARIO_ID), 'refresco');
  });

  it('muestra los instrumentos con su precio con punto de miles y su estado en texto', async () => {
    simularApi({
      [`GET /api/usuarios/${USUARIO_ID}`]: () => json(perfil()),
      'GET /api/instrumentos': () => json(INSTRUMENTOS),
    });
    renderizarApp('/mercado');

    const tabla = await screen.findByRole('table');
    const filas = within(tabla).getAllByRole('row').slice(1);
    expect(filas).toHaveLength(3);
    const andx = within(tabla).getByRole('rowheader', { name: 'ANDX' }).closest('tr');
    expect(andx).toHaveTextContent('Andes Energía');
    expect(andx).toHaveTextContent('5.000');
    expect(andx).toHaveTextContent('Activo');
    // RNF-42: el suspendido se lee, no solo se ve en otro color
    expect(within(tabla).getByRole('rowheader', { name: 'TAYR' }).closest('tr')).toHaveTextContent('Suspendido');
    expect(within(tabla).getByText('12.500')).toBeInTheDocument();
  });

  it('el buscador filtra por símbolo o nombre sin importar mayúsculas', async () => {
    simularApi({
      [`GET /api/usuarios/${USUARIO_ID}`]: () => json(perfil()),
      'GET /api/instrumentos': () => json(INSTRUMENTOS),
    });
    const { usuario } = renderizarApp('/mercado');
    await screen.findByRole('table');

    await usuario.type(screen.getByLabelText('Buscar por símbolo o nombre'), 'and');

    const tabla = screen.getByRole('table');
    expect(within(tabla).getAllByRole('row').slice(1)).toHaveLength(1);
    expect(within(tabla).getByRole('rowheader', { name: 'ANDX' })).toBeInTheDocument();

    await usuario.clear(screen.getByLabelText('Buscar por símbolo o nombre'));
    await usuario.type(screen.getByLabelText('Buscar por símbolo o nombre'), 'zzz');
    expect(screen.getByText('Ningún instrumento coincide con tu búsqueda.')).toBeInTheDocument();
  });

  it('si falla, muestra el error y se puede reintentar', async () => {
    let intentos = 0;
    simularApi({
      [`GET /api/usuarios/${USUARIO_ID}`]: () => json(perfil()),
      'GET /api/instrumentos': () => {
        intentos++;
        return intentos === 1
          ? problema(503, 'No disponible', 'market-data-service no responde')
          : json(INSTRUMENTOS);
      },
    });
    const { usuario } = renderizarApp('/mercado');

    // Un 5xx no muestra detalles internos
    expect(await screen.findByRole('alert')).toHaveTextContent('Algo salió mal de nuestro lado');
    await usuario.click(screen.getByRole('button', { name: 'Intentar de nuevo' }));

    expect(await screen.findByRole('table')).toBeInTheDocument();
  });
});

describe('filtrarInstrumentos', () => {
  const lista = INSTRUMENTOS as Instrumento[];

  it('ignora tildes, mayúsculas y espacios alrededor', () => {
    expect(filtrarInstrumentos(lista, '  ENERGIA ').map((i) => i.simbolo)).toEqual(['ANDX']);
    expect(filtrarInstrumentos(lista, 'café').map((i) => i.simbolo)).toEqual(['CAFE']);
    expect(filtrarInstrumentos(lista, '')).toHaveLength(3);
  });
});
