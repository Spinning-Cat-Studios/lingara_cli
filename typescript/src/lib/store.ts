// Token storage (ADR 16.10.26ad D4): one `CredentialStore` interface with a
// keychain adapter (`@napi-rs/keyring`), a file adapter and an in-memory
// fake, and the one rule that picks between them. `LINGARA_KEYRING=file`
// uses the file silently; `=keychain` makes an unavailable keychain
// `auth_required`; unset tries the keychain and falls back to the file,
// warning once for each profile it first writes there. A keychain module
// that fails to load is an unavailable keychain, never a crash.
import { chmodSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

import { authRequired, failure, Fail } from './fail.js'

export interface ProfileRecord {
  developer_id: string
  email: string
  /** The token response's space-separated `scope`, rewritten at each refresh. */
  scopes: string
  refresh_token: string
  access_token: string
  /** RFC 3339, UTC. */
  access_expires_at: string
}

/** No keychain on this machine (a headless Linux box, say). */
export class Unavailable extends Error {}

export interface CredentialStore {
  load(profile: string): Promise<ProfileRecord | undefined>
  save(profile: string, record: ProfileRecord): Promise<void>
  delete(profile: string): Promise<void>
}

interface CredentialsFile {
  schema: number
  profiles: Record<string, ProfileRecord>
}

export class FileStore implements CredentialStore {
  readonly path: string

  constructor(dir: string) {
    this.path = join(dir, 'credentials.json')
  }

  private read(): CredentialsFile {
    let text: string
    try {
      text = readFileSync(this.path, 'utf8')
    } catch {
      return { schema: 1, profiles: {} }
    }
    const file = JSON.parse(text) as Partial<CredentialsFile>
    return { schema: 1, profiles: file.profiles ?? {} }
  }

  private write(file: CredentialsFile): void {
    mkdirSync(dirname(this.path), { recursive: true, mode: 0o700 })
    chmodSync(dirname(this.path), 0o700)
    writeFileSync(this.path, `${JSON.stringify(file, null, 2)}\n`, { mode: 0o600 })
    chmodSync(this.path, 0o600)
  }

  has(profile: string): boolean {
    return profile in this.read().profiles
  }

  async load(profile: string): Promise<ProfileRecord | undefined> {
    return this.read().profiles[profile]
  }

  async save(profile: string, record: ProfileRecord): Promise<void> {
    const file = this.read()
    file.profiles[profile] = record
    this.write(file)
  }

  /** Removes the profile; the file goes when it held only that one. */
  async delete(profile: string): Promise<void> {
    const file = this.read()
    if (!(profile in file.profiles)) return
    delete file.profiles[profile]
    if (Object.keys(file.profiles).length === 0) rmSync(this.path, { force: true })
    else this.write(file)
  }
}

/** Service `lingara-cli`, account the profile, value the record as JSON. */
export class KeychainStore implements CredentialStore {
  private async entry(profile: string): Promise<{ getPassword(): string | null; setPassword(p: string): void; deletePassword(): boolean }> {
    try {
      const { Entry } = await import('@napi-rs/keyring')
      return new Entry('lingara-cli', profile)
    } catch (e) {
      throw new Unavailable(String(e))
    }
  }

  private async attempt<T>(f: () => T): Promise<T> {
    try {
      return f()
    } catch (e) {
      throw new Unavailable(String(e))
    }
  }

  async load(profile: string): Promise<ProfileRecord | undefined> {
    const entry = await this.entry(profile)
    const text = await this.attempt(() => entry.getPassword())
    return text === null ? undefined : (JSON.parse(text) as ProfileRecord)
  }

  async save(profile: string, record: ProfileRecord): Promise<void> {
    const entry = await this.entry(profile)
    await this.attempt(() => entry.setPassword(JSON.stringify(record)))
  }

  async delete(profile: string): Promise<void> {
    const entry = await this.entry(profile)
    await this.attempt(() => entry.deletePassword())
  }
}

/** The unit tests' fake, which can also play a keychain that is not there. */
export class MemoryStore implements CredentialStore {
  records = new Map<string, ProfileRecord>()

  constructor(public unavailable = false) {}

  private open(): void {
    if (this.unavailable) throw new Unavailable('no keychain here')
  }

  async load(profile: string): Promise<ProfileRecord | undefined> {
    this.open()
    return this.records.get(profile)
  }

  async save(profile: string, record: ProfileRecord): Promise<void> {
    this.open()
    this.records.set(profile, record)
  }

  async delete(profile: string): Promise<void> {
    this.open()
    this.records.delete(profile)
  }
}

export type Mode = 'auto' | 'file' | 'keychain'

export const modeOf = (value: string | undefined): Mode => (value === 'file' || value === 'keychain' ? value : 'auto')

function broken(e: unknown): Fail {
  if (e instanceof Unavailable) return authRequired(`the keychain is unavailable: ${e.message}`)
  return e instanceof Fail ? e : failure(`the credential store could not be read: ${String(e)}`)
}

/** The store a run uses, chosen by `Mode`. */
export class Stores {
  constructor(
    public mode: Mode,
    public keychain: CredentialStore,
    public file: FileStore,
  ) {}

  async load(profile: string): Promise<ProfileRecord | undefined> {
    if (this.mode === 'file') return this.file.load(profile).catch((e) => Promise.reject(broken(e)))
    if (this.mode === 'keychain') return this.keychain.load(profile).catch((e) => Promise.reject(broken(e)))
    const found = await this.keychain.load(profile).catch((e: unknown) => (e instanceof Unavailable ? undefined : Promise.reject(broken(e))))
    return found ?? this.file.load(profile)
  }

  /** Saves `record`; the warning to print when this write fell back to the file silently for the first time. */
  async save(profile: string, record: ProfileRecord): Promise<string | undefined> {
    if (this.mode === 'file') return this.file.save(profile, record).then(() => undefined)
    if (this.mode === 'keychain') return this.keychain.save(profile, record).then(() => undefined, (e) => Promise.reject(broken(e)))
    try {
      await this.keychain.save(profile, record)
      return undefined
    } catch (e) {
      if (!(e instanceof Unavailable)) throw broken(e)
    }
    const first = !this.file.has(profile)
    await this.file.save(profile, record)
    return first ? `storing credentials in ${this.file.path}; set LINGARA_KEYRING=keychain to require the keychain` : undefined
  }

  /** Forgets `profile` everywhere it might be. */
  async delete(profile: string): Promise<void> {
    if (this.mode !== 'file') await this.keychain.delete(profile).catch((e: unknown) => (this.mode === 'keychain' ? Promise.reject(broken(e)) : undefined))
    if (this.mode !== 'keychain') await this.file.delete(profile)
  }
}
