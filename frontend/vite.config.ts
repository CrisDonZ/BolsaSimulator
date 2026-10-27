import react from '@vitejs/plugin-react';
import type { Plugin } from 'vite';
import { defineConfig } from 'vitest/config';

/**
 * Content-Security-Policy de la app construida (RNF-25): solo scripts, estilos, fuentes y conexiones del mismo origen,
 * sin CDN ni código en línea. Va solo en el build: el servidor de desarrollo necesita un script en línea y un WebSocket
 * para recargar en caliente. frame-ancestors no se puede poner en un meta: lo manda vite preview como header.
 */
const CSP = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self'",
  "img-src 'self' data:",
  "font-src 'self'",
  "connect-src 'self'",
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
].join('; ');

function politicaDeSeguridad(): Plugin {
  return {
    name: 'bolsasim-csp',
    apply: 'build',
    transformIndexHtml: () => [
      { tag: 'meta', attrs: { 'http-equiv': 'Content-Security-Policy', content: CSP }, injectTo: 'head-prepend' },
    ],
  };
}

// El navegador solo habla con un origen: en desarrollo, Vite reenvía /api al gateway, así no hace falta abrir CORS
const proxy = {
  '/api': { target: 'http://localhost:8080', changeOrigin: false },
};

export default defineConfig({
  plugins: [react(), politicaDeSeguridad()],
  server: { port: 5173, strictPort: true, proxy },
  preview: {
    port: 4173,
    strictPort: true,
    proxy,
    headers: {
      'Content-Security-Policy': `${CSP}; frame-ancestors 'none'`,
      'X-Content-Type-Options': 'nosniff',
      'Referrer-Policy': 'no-referrer',
    },
  },
  build: { sourcemap: false },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/pruebas/preparar.ts'],
    restoreMocks: true,
  },
});
