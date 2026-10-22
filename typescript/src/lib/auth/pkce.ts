// `login`'s default: PKCE over a loopback redirect (RFC 8252, 16.10.26u §6).
// The kit listens on `127.0.0.1:0`, opens the portal's authorize page, and
// accepts exactly one callback whose `state` matches and whose `iss` is the
// portal base (RFC 9207). The listener gives up after 5 minutes.
import { createHash, randomBytes } from 'node:crypto'
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import type { AddressInfo } from 'node:net'

import { message } from '../assets.js'
import type { Ctx } from '../ctx.js'
import { Exit, Fail, authRequired } from '../fail.js'
import { fixed } from '../routes.js'
import { SCOPES } from './tokens.js'

const LISTEN_FOR_MS = 300_000
const CLOSE_TAB = '<!doctype html><title>Lingara</title><p>You can close this tab.</p>'

const randomText = (): string => randomBytes(32).toString('base64url')
export const challenge = (verifier: string): string => createHash('sha256').update(verifier).digest('base64url')

/** What the browser brought back: a code, or a declined sign-in. */
type Callback = { code: string } | { declined: true }

function listen(state: string, issuer: string): { port: Promise<number>; callback: Promise<Callback>; close(): void } {
  let settle: (c: Callback) => void = () => {}
  const callback = new Promise<Callback>((resolve) => (settle = resolve))
  const server = createServer((req: IncomingMessage, res: ServerResponse) => {
    const url = new URL(req.url ?? '/', 'http://127.0.0.1')
    const p = url.searchParams
    const ours = url.pathname === '/callback' && p.get('state') === state && p.get('iss')?.replace(/\/+$/, '') === issuer
    res.writeHead(ours ? 200 : 400, { 'content-type': 'text/html', connection: 'close' }).end(CLOSE_TAB)
    if (ours) settle(p.get('code') === null ? { declined: true } : { code: p.get('code') as string })
  })
  const port = new Promise<number>((resolve, reject) => {
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => resolve((server.address() as AddressInfo).port))
  })
  return { port, callback, close: () => server.close() }
}

/** The token response for the code the browser brought back. */
export async function browserLogin(ctx: Ctx): Promise<Record<string, unknown>> {
  const [verifier, state] = [randomText(), randomText()]
  const issuer = ctx.app.http.bases.portal.replace(/\/+$/, '')
  const loopback = listen(state, issuer)
  try {
    const redirect = `http://127.0.0.1:${await loopback.port}/callback`
    const clientId = ctx.app.endpoints.client_id
    const query: [string, string][] = [
      ['response_type', 'code'],
      ['client_id', clientId],
      ['redirect_uri', redirect],
      ['code_challenge', challenge(verifier)],
      ['code_challenge_method', 'S256'],
      ['state', state],
      ['scope', SCOPES],
    ]
    const url = ctx.app.http.url(fixed('oauth.authorize'), {}, query)
    ctx.out.note(url)
    ctx.out.note(message('login_browser_wait'))
    await ctx.app.browser(url)
    const answer = await withDeadline(loopback.callback)
    if ('declined' in answer) throw new Fail(Exit.authRequired, 'access_denied', 'sign-in was declined')
    const pairs: [string, string][] = [['grant_type', 'authorization_code'], ['code', answer.code], ['redirect_uri', redirect], ['code_verifier', verifier], ['client_id', clientId]]
    return await ctx.tokenPost('oauth.token', pairs)
  } finally {
    loopback.close()
  }
}

async function withDeadline<T>(p: Promise<T>): Promise<T> {
  let timer: NodeJS.Timeout | undefined
  const late = new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(authRequired('no sign-in arrived within 5 minutes; run `lingara login` again')), LISTEN_FOR_MS)
  })
  try {
    return await Promise.race([p, late])
  } finally {
    clearTimeout(timer)
  }
}
