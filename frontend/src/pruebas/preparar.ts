import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';
import { almacenTokens } from '../api/almacenTokens';

afterEach(() => {
  cleanup();
  almacenTokens.limpiar();
  sessionStorage.clear();
});
