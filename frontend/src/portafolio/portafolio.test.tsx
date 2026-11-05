import { screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { almacenTokens } from '../api/almacenTokens';
import { json, perfil, problema, simularApi, tokenDePrueba, USUARIO_ID } from '../pruebas/apiSimulada';
import { renderizarApp } from '../pruebas/renderizar';

const PORTAFOLIO = {
  saldoDisponible: 8_350_000,
  saldoReservado: 50_000,
  saldoTotal: 8_400_000,
  valorAcciones: 1_650_000,
  valorTotal: 10_050_000,
  posiciones: [
    {
      simbolo: 'ANDX',
      cantidad: 200,
      cantidadReservada: 50,
      precioPromedio: 5500.0,
      precioActual: 6_000,
      costo: 1_100_000,
      valorActual: 1_200_000,
      ganancia: 100_000,
      gananciaPorcentaje: 9.09,
    },
    {
      simbolo: 'CAFE',
      cantidad: 36,
      cantidadReservada: 0,
      precioPromedio: 13_888.8889,
      precioActual: 12_500,
      costo: 500_000,
      valorActual: 450_000,
      ganancia: -50_000,
      gananciaPorcentaje: -10.0,
    },
  ],
};

describe('Portafolio (HU-11)', () => {
  beforeEach(() => {
    almacenTokens.guardar(tokenDePrueba(USUARIO_ID), 'refresco');
  });

  it('muestra el valor total y cada acción con su precio promedio, su valor y su ganancia con signo', async () => {
    simularApi({
      [`GET /api/usuarios/${USUARIO_ID}`]: () => json(perfil()),
      'GET /api/portafolio': () => json(PORTAFOLIO),
    });
    renderizarApp('/portafolio');

    const tabla = await screen.findByRole('table');
    expect(screen.getByText('Valor total').nextElementSibling).toHaveTextContent('10.050.000');
    expect(screen.getByText('Disponible').nextElementSibling).toHaveTextContent('8.350.000');

    const andx = within(tabla).getByRole('rowheader', { name: 'ANDX' }).closest('tr');
    expect(andx).toHaveTextContent('5.500');
    expect(andx).toHaveTextContent('1.200.000');
    expect(andx).toHaveTextContent('+100.000');
    expect(andx).toHaveTextContent('+9,09 %');
    expect(andx).toHaveTextContent('50 en venta');
    // RNF-42: la pérdida se lee en el signo, no solo en el color
    const cafe = within(tabla).getByRole('rowheader', { name: 'CAFE' }).closest('tr');
    expect(cafe).toHaveTextContent('13.888,89');
    expect(cafe).toHaveTextContent('-50.000');
    expect(cafe).toHaveTextContent('-10,00 %');
  });

  it('sin acciones lo dice, y el valor total es el saldo', async () => {
    simularApi({
      [`GET /api/usuarios/${USUARIO_ID}`]: () => json(perfil()),
      'GET /api/portafolio': () =>
        json({ ...PORTAFOLIO, valorAcciones: 0, valorTotal: 10_000_000, posiciones: [] }),
    });
    renderizarApp('/portafolio');

    expect(await screen.findByText(/Todavía no tienes acciones/)).toBeInTheDocument();
    expect(screen.getByText('Valor total').nextElementSibling).toHaveTextContent('10.000.000');
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('Actualizar vuelve a consultar y muestra los valores nuevos', async () => {
    let consultas = 0;
    const api = simularApi({
      [`GET /api/usuarios/${USUARIO_ID}`]: () => json(perfil()),
      'GET /api/portafolio': () => {
        consultas++;
        return json(consultas === 1 ? PORTAFOLIO : { ...PORTAFOLIO, valorTotal: 10_200_000 });
      },
    });
    const { usuario } = renderizarApp('/portafolio');
    await screen.findByRole('table');

    await usuario.click(screen.getByRole('button', { name: 'Actualizar' }));

    expect(await screen.findByText('10.200.000')).toBeInTheDocument();
    expect(api.a('/api/portafolio')).toHaveLength(2);
    expect(api.a('/api/portafolio')[0]?.autorizacion).toMatch(/^Bearer /);
  });

  it('a un administrador le muestra el mensaje del servicio', async () => {
    simularApi({
      [`GET /api/usuarios/${USUARIO_ID}`]: () => json(perfil()),
      'GET /api/portafolio': () => problema(404, 'Sin cuenta', 'Los administradores no tienen cuenta de inversión.'),
    });
    renderizarApp('/portafolio');

    expect(await screen.findByRole('alert')).toHaveTextContent('Los administradores no tienen cuenta de inversión.');
  });

  it('el encabezado lleva a Mercado y a Portafolio, y marca dónde está', async () => {
    simularApi({
      [`GET /api/usuarios/${USUARIO_ID}`]: () => json(perfil()),
      'GET /api/portafolio': () => json(PORTAFOLIO),
      'GET /api/instrumentos': () => json([]),
    });
    const { usuario } = renderizarApp('/portafolio');
    const navegacion = await screen.findByRole('navigation', { name: 'Principal' });

    expect(within(navegacion).getByRole('link', { name: 'Portafolio' })).toHaveAttribute('aria-current', 'page');
    await usuario.click(within(navegacion).getByRole('link', { name: 'Mercado' }));

    expect(await screen.findByRole('heading', { name: 'Mercado' })).toBeInTheDocument();
  });
});
