// `platform.toml`'s refusals, writing rules and git checks (ADR 16.10.26ae
// D4, AC6 and AC23), against files in a temp directory. The Rust kit's
// `platform_toml_tests.rs` carries the same tests under the same names.
import { spawnSync } from 'node:child_process'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import { describe, expect, it } from 'vitest'

import { load } from '../platformToml.js'
import { Exit, Fail } from '../lib/fail.js'
import { ignored, tracked } from '../lib/project/git.js'
import { EXAMPLE_HEADER, ensureGitignore, FILE_HEADER, pinRow, removeRow, setId } from '../lib/project/write.js'

const scratch = (): string => mkdtempSync(join(tmpdir(), 'lingara-ts-'))

function file(dir: string, text: string): string {
  const path = join(dir, 'platform.toml')
  writeFileSync(path, text)
  return path
}

const refusal = (path: string): Fail => {
  try {
    load(path, 'platform.toml')
  } catch (e) {
    return e as Fail
  }
  throw new Error('expected a refusal')
}

describe('platform.toml', () => {
  /** 16.10.26ae AC6: a secret-shaped value or key is refused naming the key, never the value. */
  it('secret_values_and_keys_are_refused_by_name', () => {
    const dir = scratch()
    const row = '[[apps]]\nname = "a"\npath = "."\n[apps.env]\n'
    const refused: [string, string][] = [
      ['HOOK = "lgr_whsec_abcdefgh12345"\n', 'apps.env.HOOK'],
      ['client_secret = "x"\n', 'apps.env.client_secret'],
      ['API_TOKEN = "x"\n', 'apps.env.API_TOKEN'],
      ['db_password = "x"\n', 'apps.env.db_password'],
    ]
    for (const [line, key] of refused) {
      const fail = refusal(file(dir, `schema = 1\n${row}${line}`))
      expect(fail.exit).toBe(Exit.platformTomlSecret)
      expect(fail.message).toContain(key)
      expect(fail.message).not.toContain('lgr_whsec_abcdefgh12345')
    }
    expect(load(file(dir, `schema = 1\n${row}LINGARA_CLIENT_ID = "lgr_cid_abcdefgh12345"\nTOKENIZER = "bpe"\n`), 'platform.toml').apps).toHaveLength(1)
    expect(refusal(file(dir, 'schema = 2\n')).exit).toBe(Exit.platformTomlSchema)
    expect(refusal(file(dir, 'schema = 1\n[[apps]]\nname = "a"\npath = "."\nlisted = true\n')).exit).toBe(Exit.platformTomlSchema)
  })

  it('rows_are_written_as_the_spec_spells_them', () => {
    const dir = scratch()
    const path = join(dir, 'platform.toml')
    pinRow(path, { name: 'demo', path: '.', port: 8787, start: 'npm start', manifest: ['manifest', 'lingara/connection.js'] }, undefined)
    setId(path, 'demo', '7d1e2f30-0000-4000-8000-000000000001')
    const row = '\n[[apps]]\nname = "demo"\nid = "7d1e2f30-0000-4000-8000-000000000001"\npath = "."\nport = 8787\nstart = "npm start"\nmanifest = "lingara/connection.js"\n'
    expect(readFileSync(path, 'utf8')).toBe(FILE_HEADER + row)
    const example = readFileSync(join(dir, 'platform.toml.example'), 'utf8')
    expect(example.startsWith(EXAMPLE_HEADER) && example.includes('id = "00000000-0000-0000-0000-000000000000"')).toBe(true)
    expect(load(path, 'platform.toml').apps[0].id).toBeDefined()
    expect(removeRow(path, 'demo')).toBe(true)
    expect(readFileSync(path, 'utf8')).toBe(FILE_HEADER)
    ensureGitignore(dir)
    ensureGitignore(dir)
    expect(readFileSync(join(dir, '.gitignore'), 'utf8')).toBe('platform.toml\n')
  })

  /** 16.10.26ae AC23: in a git repository a tracked platform.toml is seen and an unignored path is not ignored; outside one both checks are skipped. */
  it('git_checks_warn_refuse_and_skip_outside_a_repo', () => {
    const outside = scratch()
    expect(tracked(file(outside, 'schema = 1\n'))).toBe(false)
    expect(ignored(join(outside, '.env.local'))).toBe(true)
    const repo = scratch()
    expect(spawnSync('git', ['-C', repo, 'init', '-q']).status).toBe(0)
    const path = file(repo, 'schema = 1\n')
    expect(spawnSync('git', ['-C', repo, 'add', 'platform.toml']).status).toBe(0)
    expect(tracked(path)).toBe(true)
    expect(ignored(join(repo, '.env.local'))).toBe(false)
    writeFileSync(join(repo, '.gitignore'), '.env.local\n')
    expect(ignored(join(repo, '.env.local'))).toBe(true)
  })
})
