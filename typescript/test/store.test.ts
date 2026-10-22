import { copyFileSync, existsSync, mkdtempSync, readFileSync, statSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'

import { describe, expect, it } from 'vitest'

import { Exit, Fail } from '../src/lib/fail.js'
import { FileStore, MemoryStore, type Mode, type ProfileRecord, Stores } from '../src/lib/store.js'

const record = (email: string): ProfileRecord => ({ developer_id: '', email, scopes: '', refresh_token: 'lgr_rt_x', access_token: 'lgr_at_x', access_expires_at: '' })

const temp = (): string => mkdtempSync(join(tmpdir(), 'lingara-store-'))
const stores = (mode: Mode, keychain: MemoryStore, dir: string): Stores => new Stores(mode, keychain, new FileStore(join(dir, 'lingara')))
const modeOf = (path: string): number => statSync(path).mode & 0o777

describe('16.10.26ad AC13 keychain first, file fallback, never config', () => {
  it('uses a keychain that is there, and never writes the file', async () => {
    const dir = temp()
    const present = stores('auto', new MemoryStore(), dir)
    expect(await present.save('default', record('a@b.c'))).toBeUndefined()
    expect(await present.load('default')).toEqual(record('a@b.c'))
    expect(existsSync(present.file.path)).toBe(false)
  })

  it('falls back to a 0600 file in a 0700 directory, warning once per profile', async () => {
    const fallback = stores('auto', new MemoryStore(true), temp())
    expect(await fallback.save('default', record('a@b.c'))).toContain('LINGARA_KEYRING=keychain')
    expect(await fallback.save('default', record('a@b.c'))).toBeUndefined()
    expect(await fallback.load('default')).toEqual(record('a@b.c'))
    expect([modeOf(fallback.file.path), modeOf(dirname(fallback.file.path))]).toEqual([0o600, 0o700])
    const shape = JSON.parse(readFileSync(fallback.file.path, 'utf8')) as { schema: number; profiles: Record<string, ProfileRecord> }
    expect([shape.schema, shape.profiles.default.email]).toEqual([1, 'a@b.c'])
  })

  it('is silent under LINGARA_KEYRING=file, and auth_required with no keychain under =keychain', async () => {
    const dir = temp()
    expect(await stores('file', new MemoryStore(), dir).save('p', record('x@y.z'))).toBeUndefined()
    const strict = stores('keychain', new MemoryStore(true), dir)
    const fail = await strict.save('p', record('x@y.z')).catch((e: Fail) => e)
    expect((fail as Fail).exit).toBe(Exit.authRequired)
    await expect(strict.load('p')).rejects.toBeInstanceOf(Fail)
    expect(existsSync(join(dir, 'lingara', 'config.toml'))).toBe(false)
  })

  it('reads the committed stored.json fixture back as the default profile', async () => {
    const dir = temp()
    copyFileSync(new URL('../../conformance/fixtures/credentials/stored.json', import.meta.url), join(dir, 'credentials.json'))
    const stored = await new FileStore(dir).load('default')
    expect(stored?.email).toBe('dev@conformance.invalid')
    expect(stored?.access_token).toBe('lgr_at_conformance_stored')
    expect(stored?.access_expires_at).toBe('2099-01-01T00:00:00Z')
  })

  it('forgets a profile, and the file with its last one', async () => {
    const s = stores('file', new MemoryStore(), temp())
    await s.save('a', record('a@b.c'))
    await s.save('b', record('b@b.c'))
    await s.delete('a')
    expect([await s.load('a'), (await s.load('b'))?.email]).toEqual([undefined, 'b@b.c'])
    await s.delete('b')
    expect(existsSync(s.file.path)).toBe(false)
  })
})
