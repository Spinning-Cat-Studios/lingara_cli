// Requests: one function spells a route onto a base, adds contract B5's
// headers, retries what D6 lets it retry, and hands back the raw answer.
// Classifying it is `classify.ts`'s; credentials are the session's. HTTP is
// Node's global `fetch`, so there is no HTTP dependency (D10).
import { Exit, Fail } from './fail.js'
import type { Route } from './routes.js'

export type Json = Record<string, unknown> | unknown[] | string | number | boolean | null

export interface WireRequest {
  method: string
  url: string
  headers: Record<string, string>
  body?: string
}

export interface WireResponse {
  status: number
  headers: Record<string, string>
  body: string
}

/** A rejected promise is a connection failure: contract B2 rule 7. */
export interface Transport {
  send(request: WireRequest): Promise<WireResponse>
}

export const fetchTransport: Transport = {
  async send(request) {
    const response = await fetch(request.url, { method: request.method, headers: request.headers, body: request.body, redirect: 'manual' })
    const headers: Record<string, string> = {}
    response.headers.forEach((v, k) => (headers[k.toLowerCase()] = v))
    return { status: response.status, headers, body: await response.text() }
  },
}

/** The time a run reads and waits on, so tests never sleep. */
export interface Clock {
  now(): number
  sleep(secs: number): Promise<void>
}

export const systemClock: Clock = {
  now: () => Math.floor(Date.now() / 1000),
  sleep: (secs) => new Promise((resolve) => setTimeout(resolve, secs * 1000)),
}

export type Body = { json: unknown } | { form: [string, string][] } | undefined
export type Auth = { bearer: string } | { basic: [string, string] } | undefined

export interface Request {
  route: Route
  vars?: Record<string, string>
  query?: [string, string][]
  body?: Body
}

/** RFC 3986 unreserved characters pass; everything else is `%XX`. */
export function encode(s: string): string {
  return Array.from(new TextEncoder().encode(s), (b) => {
    const c = String.fromCharCode(b)
    return /[A-Za-z0-9\-._~]/.test(c) ? c : `%${b.toString(16).toUpperCase().padStart(2, '0')}`
  }).join('')
}

export const form = (pairs: [string, string][]): string => pairs.map(([k, v]) => `${encode(k)}=${encode(v)}`).join('&')

/** At most two retries of an idempotent request, waiting at most 30 s in all (D6). */
const RETRIES = 2
const RETRY_BUDGET_SECS = 30

export class Http {
  constructor(
    public transport: Transport,
    public clock: Clock,
    public bases: { api: string; portal: string },
    public userAgent: string,
    public acceptLanguage?: string,
  ) {}

  url(route: Route, vars: Record<string, string> = {}, query: [string, string][] = []): string {
    const base = (route.base === 'portal' ? this.bases.portal : this.bases.api).replace(/\/+$/, '')
    const path = Object.entries(vars).reduce((p, [k, v]) => p.split(`{${k}}`).join(encode(v)), route.path)
    const q = query.length === 0 ? '' : `?${form(query)}`
    return `${base}${path}${q}`
  }

  wire(request: Request, auth: Auth): WireRequest {
    const headers: Record<string, string> = { 'user-agent': this.userAgent, accept: 'application/json' }
    if (this.acceptLanguage !== undefined) headers['accept-language'] = this.acceptLanguage
    if (auth !== undefined && 'bearer' in auth) headers.authorization = `Bearer ${auth.bearer}`
    if (auth !== undefined && 'basic' in auth) headers.authorization = basic(auth.basic)
    let body: string | undefined
    if (request.body !== undefined && 'json' in request.body) {
      headers['content-type'] = 'application/json'
      body = JSON.stringify(request.body.json)
    } else if (request.body !== undefined && 'form' in request.body) {
      headers['content-type'] = 'application/x-www-form-urlencoded'
      body = form(request.body.form)
    }
    return { method: request.route.method, url: this.url(request.route, request.vars, request.query), headers, body }
  }

  /** One request with D6's retries; a `POST` is never retried after a response (B5). */
  async send(request: Request, auth: Auth): Promise<WireResponse> {
    const wire = this.wire(request, auth)
    let waited = 0
    for (let attempt = 0; ; attempt += 1) {
      const response = await this.transport.send(wire).catch((e: unknown) => {
        throw new Fail(Exit.unavailable, 'unavailable', `${request.route.name} could not be reached: ${String(e)}`)
      })
      if (attempt > 0 && replayedDeleteDone(wire, response)) return { status: 204, headers: {}, body: '' }
      const wait = retryWait(wire, response)
      if (wait === undefined || attempt >= RETRIES || waited + wait > RETRY_BUDGET_SECS) return response
      await this.clock.sleep(wait)
      waited += wait
    }
  }
}

function retryWait(wire: WireRequest, response: WireResponse): number | undefined {
  const idempotent = ['GET', 'PUT', 'PATCH', 'DELETE'].includes(wire.method)
  if (!idempotent || (response.status !== 429 && response.status !== 503)) return undefined
  const secs = Number.parseInt(response.headers['retry-after'] ?? '', 10)
  return Number.isNaN(secs) ? undefined : secs
}

/** A `DELETE` replayed after it succeeded answers a coded 404 (D6). */
function replayedDeleteDone(wire: WireRequest, response: WireResponse): boolean {
  return wire.method === 'DELETE' && response.status === 404 && typeof parse(response.body)?.code === 'string'
}

export function parse(body: string): Record<string, unknown> | undefined {
  try {
    const v: unknown = JSON.parse(body)
    return typeof v === 'object' && v !== null && !Array.isArray(v) ? (v as Record<string, unknown>) : undefined
  } catch {
    return undefined
  }
}

function basic([id, secret]: [string, string]): string {
  return `Basic ${Buffer.from(`${encode(id)}:${encode(secret)}`).toString('base64')}`
}
