// @ts-check
const eslint = require('@eslint/js');
const tseslint = require('typescript-eslint');
const angular = require('angular-eslint');

module.exports = tseslint.config(
  {
    ignores: ['.angular/**', 'coverage/**', 'dist/**', 'node_modules/**', 'out-tsc/**', 'reports/**']
  },
  {
    files: ['**/*.ts'],
    extends: [
      eslint.configs.recommended,
      ...tseslint.configs.recommended,
      ...angular.configs.tsRecommended
    ],
    processor: angular.processInlineTemplates,
    rules: {
      '@angular-eslint/component-selector': [
        'error',
        { type: 'element', prefix: 'app', style: 'kebab-case' }
      ],
      '@angular-eslint/directive-selector': [
        'error',
        { type: 'attribute', prefix: 'app', style: 'camelCase' }
      ],
      // Constructor injection is valid in this existing codebase. Converting it is a separate refactor.
      '@angular-eslint/prefer-inject': 'off'
    }
  },
  {
    files: ['**/*.html'],
    extends: [...angular.configs.templateRecommended],
    rules: {
      // Keep existing accessibility gaps visible without blocking the initial CI rollout.
      // These warnings should be fixed incrementally by using native interactive elements
      // or by adding complete keyboard and ARIA behavior.
      '@angular-eslint/template/click-events-have-key-events': 'warn',
      '@angular-eslint/template/interactive-supports-focus': 'warn',
      '@angular-eslint/template/label-has-associated-control': 'warn',
      '@angular-eslint/template/role-has-required-aria': 'warn'
    }
  }
);
