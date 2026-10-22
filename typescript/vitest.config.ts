// assets/ holds the app templates this kit scaffolds (ADR 16.10.26ae D12),
// whose own tests run in the scaffolded project, never here.
import { configDefaults, defineConfig } from 'vitest/config'

export default defineConfig({
  test: { exclude: [...configDefaults.exclude, 'assets/**'] },
})
