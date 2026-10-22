// The unit tests' world: a scripted transport, a terminal, a clock that never
// sleeps and an in-memory keychain holding the conformance fixture's
// developer. Every seam `App` has, faked once.
import { ENDPOINTS } from '../src/lib/assets.js'
import { Ctx, type Terminal } from '../src/lib/ctx.js'
import { Env } from '../src/lib/env.js'
import { Http, type Transport, type WireRequest, type WireResponse } from '../src/lib/http.js'
import { Output } from '../src/lib/output.js'
import { FileStore, MemoryStore, type ProfileRecord, Stores } from '../src/lib/store.js'

export const EMAIL = 'dev@conformance.invalid'

export const storedRecord = (): ProfileRecord => ({
  developer_id: '0b9c4f0e-0000-4000-8000-0000000000de',
  email: EMAIL,
  scopes: 'platform:read platform:write canvas:read canvas:write',
  refresh_token: 'lgr_rt_conformance_stored',
  access_token: 'lgr_at_conformance_stored',
  access_expires_at: '2099-01-01T00:00:00Z',
})

/** Requests answered in order; each names the `METHOD /path` it expects, anything else is a `599`. */
export class Script implements Transport {
  answers: [string, WireResponse][] = []
  log: WireRequest[] = []

  then(expect: string, status: number, body?: unknown, headers: Record<string, string> = {}): this {
    this.answers.push([expect, { status, headers, body: body === undefined ? '' : JSON.stringify(body) }])
    return this
  }

  sent(): string[] {
    return this.log.map((r) => `${r.method} ${new URL(r.url).pathname}${new URL(r.url).search}`)
  }

  body(i: number): unknown {
    return JSON.parse(this.log[i].body ?? 'null')
  }

  async send(request: WireRequest): Promise<WireResponse> {
    this.log.push(request)
    const url = new URL(request.url)
    const [got, path] = [`${request.method} ${url.pathname}${url.search}`, `${request.method} ${url.pathname}`]
    const next = this.answers.shift()
    if (next === undefined) throw new Error('connection reset')
    return next[0] === got || next[0] === path ? next[1] : { status: 599, headers: {}, body: `expected ${next[0]}` }
  }
}

export class FakeTerminal implements Terminal {
  constructor(
    public tty: boolean,
    public lines: string[] = [],
  ) {}

  interactive(): boolean {
    return this.tty
  }

  async readLine(): Promise<string | undefined> {
    return this.lines.shift()
  }
}

/** A context signed in as the fixture developer, over `script`; `err` is stderr's text. */
export function fakeCtx(script: Script, json = false, terminal = new FakeTerminal(false)): { ctx: Ctx; err: string[] } {
  const keychain = new MemoryStore()
  keychain.records.set('default', storedRecord())
  const http = new Http(script, { now: () => 1_760_000_000, sleep: async () => {} }, { api: 'http://mock', portal: 'http://mock' }, 'lingara-cli/0.0.0 (typescript; test)', 'en-US')
  const err: string[] = []
  const app = {
    http,
    env: new Env({}),
    stores: new Stores('keychain', keychain, new FileStore('/nonexistent')),
    terminal,
    browser: async () => false,
    endpoints: ENDPOINTS,
    run: async () => ({ exit: 0, stdout: '' }),
  }
  return { ctx: new Ctx(app, new Output({ write: (t) => err.push(t) }, json), 'default'), err }
}
