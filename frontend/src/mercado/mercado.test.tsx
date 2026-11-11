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

  it('muestra los instrumentos con su último precio con punto de miles y su estado en texto', async () => {
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
    // El precio es el último, no el inicial (4.000)
    expect(tabla).not.toHaveTextContent('4.000');
  });

  it('muestra la variación del día con su signo: sube, baja o queda igual', async () => {
    simularApi({
      [`GET /api/usuarios/${USUARIO_ID}`]: () => json(perfil()),
      'GET /api/instrumentos': () => json(INSTRUMENTOS),
    });
    renderizarApp('/mercado');
    const tabla = await screen.findByRole('table');
    const fila = (simbolo: string) => within(tabla).getByRole('rowheader', { name: simbolo }).closest('tr');

    // RNF-42: el signo va en el texto; el color solo lo acompaña
    expect(fila('ANDX')).toHaveTextContent('+200');
    expect(fila('ANDX')).toHaveTextContent('+4,17 %');
    expect(within(tabla).getByText('+4,17 %', { exact: false })).toHaveClass('ganancia');
    expect(fila('CAFE')).toHaveTextContent('-500');
    expect(fila('CAFE')).toHaveTextContent('-3,85 %');
    expect(within(tabla).getByText('-3,85 %', { exact: false })).toHaveClass('perdida');
    // Sin operaciones hoy: 0,00 %, sin signo ni color
    expect(fila('TAYR')).toHaveTextContent('0,00 %');
    expect(fila('TAYR')).not.toHaveTextContent(/[+-]0/);
    const sinCambio = within(tabla).getByText('0,00 %', { exact: false });
    expect(sinCambio).not.toHaveClass('ganancia');
    expect(sinCambio).not.toHaveClass('perdida');
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
