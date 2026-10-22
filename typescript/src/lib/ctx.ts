// One run's world and every authenticated call (ADR 16.10.26ad D3; contract
// B2 rule 2). A `401` to a bearer request refreshes once (a fresh
// `client_credentials` mint under CI) and replays once; a second `401` is
// `auth_required`, and any other answer to the replay is classified again.
import type { Endpoints } from './assets.js'
import { advise, PORTAL_LINE } from './auth/hints.js'
import { applyTokens, SCOPES, tokensOf } from './auth/tokens.js'
import { type Answer, classify, failFor, lingaraCode } from './classify.js'
import type { RitualPorts } from './confirm.js'
import type { Env } from './env.js'
import { Exit, Fail, authRequired, failure } from './fail.js'
import { type Auth, type Http, type Json, parse, type Request, type WireResponse } from './http.js'
import type { Output } from './output.js'
import { type FixedName, fixed, isPlatformRoute, isTokenRoute, type Route } from './routes.js'
import type { ProfileRecord, Stores } from './store.js'

export interface Terminal {
  interactive(): boolean
  readLine(): Promise<string | undefined>
}

export interface Session {
  ci?: [string, string]
  record?: ProfileRecord
  access: string
  /** The email `--confirm` is checked against: the record's, or `developer.me`'s under CI. */
  email?: string
  scopes: string
}

export interface App {
  http: Http
  env: Env
  stores: Stores
  terminal: Terminal
  browser(url: string): Promise<boolean>
  endpoints: Endpoints
  session?: Session
  /** The in-process entry point canvas apply's inner verbs go through (D7). */
  run(argv: string[], err: { write(text: string): void }): Promise<{ exit: number; stdout: string; fail?: Fail }>
}

const REFRESH_SKEW_SECS = 60

function answerOf(route: Route, reply: WireResponse, bearer: boolean): Answer {
  const retry = Number.parseInt(reply.headers['retry-after'] ?? '', 10)
  return { route: route.name, tokenRoute: isTokenRoute(route), platformRoute: isPlatformRoute(route), bearer, status: reply.status, body: reply.body, retryAfter: Number.isNaN(retry) ? undefined : retry }
}

/** The refusal for a non-2xx reply that is not rule 2's. */
export function refusal(route: Route, reply: WireResponse, bearer: boolean): Fail {
  const a = answerOf(route, reply, bearer)
  const verdict = classify(a)
  const fail = advise(failFor(a, verdict === 'refresh' ? Exit.authRequired : verdict))
  return lingaraCode(a) === 'developer_account_required' ? fail.hint(PORTAL_LINE) : fail
}

function bodyJson(reply: WireResponse, route: Route): Json {
  if (reply.body.trim() === '') return {}
  try {
    return JSON.parse(reply.body) as Json
  } catch {
    throw failure(`unexpected response from ${route.name}`)
  }
}

const ok = (status: number): boolean => status >= 200 && status < 300

/** A refused refresh says "run `lingara login`", unless it is D8's dark signal. */
const loginAgain = (fail: Fail): Fail => (fail.exit === Exit.authRequired ? fail.hint('run `lingara login`') : fail)

export class Ctx implements RitualPorts {
  constructor(
    public app: App,
    public out: Output,
    public profile: string,
    /** `--profile` or `LINGARA_PROFILE` chose it, so `platform.toml`'s `[platform] profile` may not (16.10.26ae D4). */
    public profileChosen = false,
  ) {}

  interactive(): boolean {
    return this.app.terminal.interactive()
  }

  readLine(): Promise<string | undefined> {
    return this.app.terminal.readLine()
  }

  prompt(text: string): void {
    this.out.prompt(text)
  }

  /** The CI pair, when both halves are set (D3 rule 1). */
  ciPair(): [string, string] | undefined {
    const id = this.app.env.get('LINGARA_PLATFORM_CLIENT_ID')
    const secret = this.app.env.get('LINGARA_PLATFORM_CLIENT_SECRET')
    return id !== undefined && secret !== undefined ? [id, secret] : undefined
  }

  /** A token-endpoint form `POST`: no bearer, classified as a token route. */
  async tokenPost(name: FixedName, pairs: [string, string][], auth?: Auth): Promise<Record<string, unknown>> {
    const route = fixed(name)
    const reply = await this.app.http.send({ route, body: { form: pairs } }, auth)
    if (!ok(reply.status)) throw refusal(route, reply, false)
    return (parse(reply.body) ?? {}) as Record<string, unknown>
  }

  private async mint([id, secret]: [string, string]): Promise<Record<string, unknown>> {
    return this.tokenPost('oauth.token', [['grant_type', 'client_credentials'], ['scope', SCOPES]], { basic: [id, secret] })
  }

  private async open(): Promise<Session> {
    const ci = this.ciPair()
    if (ci !== undefined) {
      const t = tokensOf(await this.mint(ci))
      return { ci, access: t.access, scopes: t.scope ?? SCOPES }
    }
    const record = await this.app.stores.load(this.profile)
    if (record === undefined) throw authRequired('not signed in: run `lingara login`')
    this.app.session = { record, access: record.access_token, email: record.email, scopes: record.scopes }
    const expires = Date.parse(record.access_expires_at) / 1000
    if (Number.isNaN(expires) || expires - this.app.http.clock.now() < REFRESH_SKEW_SECS) await this.refresh()
    return this.app.session
  }

  /** The bearer token, opening the session on first use. */
  async bearer(): Promise<string> {
    this.app.session ??= await this.open()
    return this.app.session.access
  }

  /** Rule 2's one refresh: the stored refresh token, or a fresh CI mint. A refused refresh never deletes the record (D3). */
  private async refresh(): Promise<void> {
    const session = this.app.session
    if (session === undefined) throw authRequired('not signed in: run `lingara login`')
    if (session.ci !== undefined) {
      session.access = tokensOf(await this.mint(session.ci)).access
      return
    }
    const record = { ...(session.record as ProfileRecord) }
    const pairs: [string, string][] = [['grant_type', 'refresh_token'], ['refresh_token', record.refresh_token], ['client_id', this.app.endpoints.client_id]]
    const answer = await this.tokenPost('oauth.token', pairs).catch((f: Fail) => Promise.reject(loginAgain(f)))
    applyTokens(record, tokensOf(answer), this.app.http.clock.now())
    await this.saveRecord(record)
    this.app.session = { record, access: record.access_token, email: record.email, scopes: record.scopes }
  }

  /** Writes the profile record, printing the store's one fallback warning. */
  async saveRecord(record: ProfileRecord): Promise<void> {
    const warning = await this.app.stores.save(this.profile, record)
    if (warning !== undefined) this.out.note(warning)
  }

  /** One authenticated call: send, refresh and replay once on a `401`, classify anything else. */
  async call(request: Request): Promise<Json> {
    const reply = await this.app.http.send(request, { bearer: await this.bearer() })
    if (ok(reply.status)) return bodyJson(reply, request.route)
    if (classify(answerOf(request.route, reply, true)) !== 'refresh') throw refusal(request.route, reply, true)
    await this.refresh()
    const replay = await this.app.http.send(request, { bearer: await this.bearer() })
    if (ok(replay.status)) return bodyJson(replay, request.route)
    if (replay.status === 401) throw authRequired('the platform refused the credentials after a refresh: run `lingara login`')
    throw refusal(request.route, replay, true)
  }

  /** The email `--confirm` and the typed line are compared against. */
  async email(): Promise<string> {
    await this.bearer()
    const known = this.app.session?.email
    if (known !== undefined) return known
    const me = (await this.call({ route: fixed('developer.me') })) as Record<string, unknown>
    const email = typeof me.email === 'string' ? me.email : ''
    if (this.app.session !== undefined) this.app.session.email = email
    return email
  }
}
