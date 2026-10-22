// `login` (ADR 16.10.26ad D3): PKCE loopback by default, `--device` for a
// machine with no browser. Under CI credentials it is `usage`.
import { Flags } from '@oclif/core'

import { message } from '../lib/assets.js'
import { browserLogin } from '../lib/auth/pkce.js'
import { deviceLogin } from '../lib/auth/device.js'
import { applyTokens, tokensOf } from '../lib/auth/tokens.js'
import { LingaraCommand, type Parsed } from '../lib/base.js'
import type { Ctx } from '../lib/ctx.js'
import { Exit, Fail } from '../lib/fail.js'
import { fixed } from '../lib/routes.js'
import type { ProfileRecord } from '../lib/store.js'

export const ciNotNeeded = (): Fail => new Fail(Exit.usage, 'login_ci_not_needed', message('login_ci_not_needed'))

export default class Login extends LingaraCommand {
  static override summary = 'Sign in through the browser, or with a device code.'
  static override flags = {
    device: Flags.boolean({ summary: 'Sign in with a code shown here, for a machine with no browser' }),
  }

  static override async exec(ctx: Ctx, input: Parsed): Promise<void> {
    if (ctx.ciPair() !== undefined) throw ciNotNeeded()
    const answer = input.flags.device === true ? await deviceLogin(ctx) : await browserLogin(ctx)
    const record: ProfileRecord = { developer_id: '', email: '', scopes: '', refresh_token: '', access_token: '', access_expires_at: '' }
    applyTokens(record, tokensOf(answer), ctx.app.http.clock.now())
    // The new token, for this one read: whose is it?
    ctx.app.session = { record: { ...record }, access: record.access_token, scopes: record.scopes }
    const me = (await ctx.call({ route: fixed('developer.me') })) as Record<string, unknown>
    record.developer_id = typeof me.developer_id === 'string' ? me.developer_id : ''
    record.email = typeof me.email === 'string' ? me.email : ''
    await ctx.saveRecord(record)
    ctx.out.note(message('login_signed_in', { email: record.email }))
    ctx.out.nothing()
  }
}
