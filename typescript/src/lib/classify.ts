// The one response classifier (ADR 16.10.26ad D8; contract B2). Its eight
// rules are **data**: an ordered list of `(predicate, verdict)` entries, the
// first match winning and rule 8 the catch-all. 16.10.26ae D10's rule 4a is
// one more entry, after rule 4.
import { message } from './assets.js'
import { Exit, type ExitCode, Fail } from './fail.js'
import { parse } from './http.js'

/** `conformance/routes.toml`'s two codes, as this kit keys them. */
export const PLATFORM_DISABLED_CODE = 'platform_disabled'
export const PLATFORM_ACCESS_CODE = 'platform_access_not_enabled'

/** A non-2xx answer, with what the rules read about the request behind it. */
export interface Answer {
  route: string
  /** `oauth.token` or `oauth.device_authorization`. */
  tokenRoute: boolean
  /** Under `/platform/`, where the dark 404 is spoken. */
  platformRoute: boolean
  /** The request carried `Authorization: Bearer` (rule 2). */
  bearer: boolean
  status: number
  body: string
  retryAfter?: number
}

export type Verdict = 'refresh' | ExitCode

const member = (a: Answer, key: string): string | undefined => {
  const v = parse(a.body)?.[key]
  return typeof v === 'string' ? v : undefined
}
const coded = (a: Answer): boolean => member(a, 'code') !== undefined && member(a, 'error') !== undefined
const oauthEnvelope = (a: Answer): boolean => member(a, 'error') !== undefined && member(a, 'code') === undefined

function platformDisabled(a: Answer): boolean {
  const code = member(a, 'code')
  const dark = a.status === 404 && a.platformRoute && code === PLATFORM_DISABLED_CODE
  const door = a.status === 403 && code === PLATFORM_ACCESS_CODE
  const token = a.tokenRoute && member(a, 'lingara_code') === PLATFORM_ACCESS_CODE
  return dark || door || token
}

/** Rule 4's one exception is keyed on route **and** code. */
const codedNotFound = (a: Answer): Verdict =>
  a.route === 'developer.me' && member(a, 'code') === 'developer_not_found' ? Exit.refused : Exit.notFound

/**
 * Rule 4a (16.10.26ae D10): a dev-session `409` is a row of its own, keyed on
 * `(route, body code)`, because a script branches on "someone else is
 * developing this app" where it would not on any other refusal.
 */
const DEV_SESSION_CODES: [string, string, ExitCode][] = [
  ['app_dev.session_open', 'dev_session_held', Exit.devSessionHeld],
  ['app_dev.patch', 'dev_session_held', Exit.devSessionHeld],
  ['app_dev.session_renew', 'dev_session_not_live', Exit.devSessionLost],
  ['app_dev.session_renew', 'dev_session_expired', Exit.devSessionLost],
]

const devSessionRow = (a: Answer): ExitCode | undefined =>
  a.status === 409 ? DEV_SESSION_CODES.find(([route, code]) => route === a.route && code === member(a, 'code'))?.[2] : undefined

const RULES: [(a: Answer) => boolean, (a: Answer) => Verdict][] = [
  [platformDisabled, () => Exit.platformDisabled],
  [(a) => a.status === 401 && a.bearer && !a.tokenRoute, () => 'refresh'],
  [(a) => a.tokenRoute && a.status >= 400 && a.status < 500 && a.status !== 429 && oauthEnvelope(a), () => Exit.authRequired],
  [(a) => a.status === 404 && coded(a), codedNotFound],
  [(a) => devSessionRow(a) !== undefined, (a) => devSessionRow(a) ?? Exit.refused],
  [(a) => a.status === 404 && a.body.trim() === '', () => Exit.failure],
  [(a) => [400, 403, 409, 422].includes(a.status), () => Exit.refused],
  [(a) => a.status === 429 || a.status >= 500, () => Exit.unavailable],
  [() => true, () => Exit.failure],
]

export function classify(answer: Answer): Verdict {
  const rule = RULES.find(([matches]) => matches(answer))
  return rule === undefined ? Exit.failure : rule[1](answer)
}

/** The code and text an exit prints: the server's own where it sent them. */
function spelled(a: Answer, exit: ExitCode): [string, string] {
  if (exit === Exit.platformDisabled) return [PLATFORM_DISABLED_CODE, message('platform_disabled')]
  if (exit === Exit.failure) return ['unexpected_response', `unexpected response from ${a.route}`]
  if (exit === Exit.authRequired) return [member(a, 'error') ?? 'auth_required', member(a, 'error_description') ?? `${a.route} refused the credentials`]
  return [member(a, 'code') ?? (exit === Exit.unavailable ? 'unavailable' : 'refused'), member(a, 'error') ?? `${a.route} answered HTTP ${a.status}`]
}

/** The refusal B2 prints, with the wait a 429 named. */
export function failFor(a: Answer, exit: ExitCode): Fail {
  const [code, text] = spelled(a, exit)
  const wait = exit === Exit.unavailable && a.retryAfter !== undefined ? ` (retry after ${a.retryAfter} s)` : ''
  const fail = new Fail(exit, code, `${text}${wait}`)
  // A command that re-words a refusal (apps dev's `dev_session_held`) reads the server's other members from here.
  fail.body = a.body
  return fail
}

export const lingaraCode = (a: Answer): string | undefined => member(a, 'lingara_code')
