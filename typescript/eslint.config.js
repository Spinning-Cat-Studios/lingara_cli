// The code-health budgets, carried into TypeScript (ADR 16.10.26ad D10).
//
// code-health cannot see TypeScript, so the kit's linter carries the numbers
// the Rust kit is scanned by: 300 lines a file (600 for a test), 50 a
// function, complexity 10, five parameters. They are the web client's, which
// keeps the same budgets the same way. `test-typescript` runs this.
import js from '@eslint/js'
import tseslint from 'typescript-eslint'

export const BUDGETS = {
  maxLines: 300,
  maxTestLines: 600,
  maxLinesPerFunction: 50,
  complexity: 10,
  maxParams: 5,
}

const budgetRules = {
  'max-lines': ['error', { max: BUDGETS.maxLines, skipBlankLines: false, skipComments: false }],
  'max-lines-per-function': ['error', { max: BUDGETS.maxLinesPerFunction, skipBlankLines: false, skipComments: false }],
  complexity: ['error', BUDGETS.complexity],
  'max-params': ['error', BUDGETS.maxParams],
}

export default tseslint.config(
  { ignores: ['dist', 'node_modules'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    files: ['**/*.ts'],
    rules: {
      ...budgetRules,
      // A base method's parameters name what overrides receive.
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
    },
  },
  {
    // A test file gets the test tier's line budget; a `describe` block is a
    // function to ESLint but a folder to a reader. `src/__tests__/` holds the
    // tests 16.10.26ae's table places beside their modules.
    files: ['test/**/*.ts', 'src/**/__tests__/**/*.ts'],
    rules: {
      'max-lines': ['error', { max: BUDGETS.maxTestLines, skipBlankLines: false, skipComments: false }],
      'max-lines-per-function': 'off',
    },
  },
)
