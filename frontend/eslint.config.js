import js from '@eslint/js';
import reactHooks from 'eslint-plugin-react-hooks';
import globals from 'globals';
import tseslint from 'typescript-eslint';

export default tseslint.config(
  { ignores: ['dist', 'node_modules'] },
  {
    files: ['**/*.{ts,tsx,js}'],
    extends: [js.configs.recommended, ...tseslint.configs.strict, reactHooks.configs.flat['recommended-latest']],
    languageOptions: { globals: globals.browser },
    rules: {
      // RNF-25: ningún texto se interpreta como HTML ni como código
      'no-eval': 'error',
      'no-implied-eval': 'error',
      'no-restricted-syntax': [
        'error',
        {
          selector: "JSXAttribute[name.name='dangerouslySetInnerHTML']",
          message: 'No se inserta HTML crudo: muestra el texto como texto (RNF-25).',
        },
        {
          selector: "MemberExpression[property.name='innerHTML']",
          message: 'No se inserta HTML crudo: muestra el texto como texto (RNF-25).',
        },
      ],
      // Los tokens y las contraseñas nunca terminan en la consola
      'no-console': 'error',
    },
  },
);
