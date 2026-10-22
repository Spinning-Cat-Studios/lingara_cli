// The literal reader against spec/connection-fixtures/, and the manifest
// warnings against spec/manifest-warnings.json: the files every kit's reader
// and rule are held to (ADR 16.10.26ae D2, D6 step 5).
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, expect, it } from 'vitest'

import { appManifest, Refusal, refusalFail, statements } from '../src/connectionLiteral.js'
import { Exit } from '../src/lib/fail.js'
import { ephemeral, locales, warnings } from '../src/lib/dev/manifest.js'

const SPEC = fileURLToPath(new URL('../../spec/', import.meta.url))
const fixtures = (kind: string): [string, string][] =>
  readdirSync(join(SPEC, 'connection-fixtures', kind))
    .filter((f) => f.endsWith('.js'))
    .sort()
    .map((f) => [f.slice(0, -3), join(SPEC, 'connection-fixtures', kind, f)])

const expected = (path: string): Record<string, unknown> | undefined => {
  try {
    return JSON.parse(readFileSync(path.replace(/\.js$/, '.json'), 'utf8')) as Record<string, unknown>
  } catch {
    return undefined
  }
}

const refusalOf = (text: string): Refusal => {
  try {
    appManifest(text)
  } catch (e) {
    return e as Refusal
  }
  throw new Error('expected a refusal')
}

describe('16.10.26ae AC5 the literal subset', () => {
  it('every_valid_fixture_converts_to_its_wire_manifest', () => {
    for (const [name, path] of fixtures('valid')) {
      const text = readFileSync(path, 'utf8')
      expect(() => statements(text), name).not.toThrow()
      const want = expected(path)
      if (want === undefined) expect(() => appManifest(text), name).toThrow()
      else expect(JSON.stringify(appManifest(text)), name).toBe(JSON.stringify(want))
    }
  })

  it('every_refused_fixture_is_refused_where_it_says', () => {
    for (const [name, path] of fixtures('refused')) {
      const want = expected(path) as Record<string, unknown>
      const got = refusalOf(readFileSync(path, 'utf8'))
      if (got.statement !== undefined) expect([want.code, want.statement], name).toEqual(['connection_scripted', got.statement])
      else expect([got.code, got.pos?.line, got.pos?.column], name).toEqual([want.code, want.line, want.column])
    }
  })

  it('a_refusal_prints_its_position_and_a_script_names_its_statement', () => {
    const fail = refusalFail(new Refusal('key', { line: 3, column: 40 }), 'lingara/connection.js')
    expect(fail.message.startsWith('lingara/connection.js:3:40: ') && fail.code === 'connection_literal').toBe(true)
    const scripted = refusalFail(new Refusal('connection_scripted', undefined, 'hooks'), 'lingara/connection.js')
    expect(scripted.exit).toBe(Exit.connectionScripted)
    expect(scripted.message).toContain('hooks')
  })
})

describe('16.10.26ae AC17 manifest warnings', () => {
  it('manifest_warnings_follow_the_shared_vectors', () => {
    const file = JSON.parse(readFileSync(join(SPEC, 'manifest-warnings.json'), 'utf8')) as {
      vectors: { name: string; manifest: Record<string, unknown>; expect: { locales: string[]; warnings: string[] } }[]
    }
    for (const v of file.vectors) {
      expect(locales(v.manifest), v.name).toEqual(v.expect.locales)
      expect(warnings(v.manifest), v.name).toEqual(v.expect.warnings)
    }
  })

  it('quick_tunnels_and_the_placeholder_are_ephemeral', () => {
    expect(ephemeral('https://dev.invalid/') && ephemeral('https://a-b.trycloudflare.com/render')).toBe(true)
    expect(ephemeral('https://user@X.TryCloudflare.com:443/')).toBe(true)
    expect(ephemeral('https://cards.example.com/render') || ephemeral('https://trycloudflare.com.example/')).toBe(false)
  })
})
