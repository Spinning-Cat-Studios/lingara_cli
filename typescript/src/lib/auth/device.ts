// `login --device` (RFC 8628, 16.10.26u §7): for SSH sessions and machines
// without a browser. `authorization_pending` keeps polling, `slow_down` adds
// 5 s, `expired_token` and `access_denied` are `auth_required`, and any
// answer carrying B4's `lingara_code` is `platform_disabled` (D8).
import { message } from '../assets.js'
import type { Ctx } from '../ctx.js'
import { refusal } from '../ctx.js'
import { Exit, Fail, authRequired } from '../fail.js'
import { parse } from '../http.js'
import { fixed } from '../routes.js'
import { SCOPES } from './tokens.js'

const GRANT = 'urn:ietf:params:oauth:grant-type:device_code'

const str = (v: unknown): string => (typeof v === 'string' ? v : '')
const num = (v: unknown, fallback: number): number => (typeof v === 'number' ? v : fallback)

/** The token response once the developer has approved the code. */
export async function deviceLogin(ctx: Ctx): Promise<Record<string, unknown>> {
  const clientId = ctx.app.endpoints.client_id
  const start = await ctx.tokenPost('oauth.device_authorization', [['client_id', clientId], ['scope', SCOPES]])
  ctx.out.note(message('login_device_code', { verification_uri: str(start.verification_uri), user_code: str(start.user_code) }))
  if (typeof start.verification_uri_complete === 'string') ctx.out.note(start.verification_uri_complete)
  return poll(ctx, str(start.device_code), num(start.interval, 5), num(start.expires_in, 900))
}

/** What one refused poll means: keep polling, poll slower, or stop with a refusal. */
function next(error: unknown, live: boolean): 'authorization_pending' | 'slow_down' | Fail | undefined {
  if ((error === 'authorization_pending' || error === 'slow_down') && live) return error
  if (error === 'authorization_pending' || error === 'slow_down' || error === 'expired_token') {
    return authRequired('the code expired before it was approved; run `lingara login --device` again')
  }
  return error === 'access_denied' ? new Fail(Exit.authRequired, 'access_denied', 'sign-in was declined') : undefined
}

async function poll(ctx: Ctx, deviceCode: string, interval: number, expiresIn: number): Promise<Record<string, unknown>> {
  const route = fixed('oauth.token')
  const pairs: [string, string][] = [['grant_type', GRANT], ['device_code', deviceCode], ['client_id', ctx.app.endpoints.client_id]]
  for (let waited = 0; ; ) {
    await ctx.app.http.clock.sleep(interval)
    waited += interval
    const reply = await ctx.app.http.send({ route, body: { form: pairs } }, undefined)
    if (reply.status >= 200 && reply.status < 300) return parse(reply.body) ?? {}
    const body = parse(reply.body) ?? {}
    // B4's `lingara_code` outranks any RFC 8628 error beside it (D8).
    const verdict = next(body.lingara_code === undefined ? body.error : undefined, waited < expiresIn)
    if (verdict === 'slow_down') interval += 5
    else if (verdict instanceof Fail) throw verdict
    else if (verdict === undefined) throw refusal(route, reply, false)
  }
}
