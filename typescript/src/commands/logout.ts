// `logout` (ADR 16.10.26ad D3): revoke the refresh token (RFC 7009) and
// forget the profile. A revoke that fails, the network being down say,
// still forgets it and warns.
import { LingaraCommand } from '../lib/base.js'
import type { Ctx } from '../lib/ctx.js'
import { authRequired } from '../lib/fail.js'
import { fixed } from '../lib/routes.js'
import { ciNotNeeded } from './login.js'

export default class Logout extends LingaraCommand {
  static override summary = 'Revoke and forget the stored credentials.'

  static override async exec(ctx: Ctx): Promise<void> {
    if (ctx.ciPair() !== undefined) throw ciNotNeeded()
    const record = await ctx.app.stores.load(ctx.profile)
    if (record === undefined) throw authRequired('not signed in: nothing to sign out of')
    const pairs: [string, string][] = [['token', record.refresh_token], ['token_type_hint', 'refresh_token'], ['client_id', ctx.app.endpoints.client_id]]
    const why = await ctx.app.http.send({ route: fixed('oauth.revoke'), body: { form: pairs } }, { bearer: record.access_token }).then(
      (r) => (r.status >= 200 && r.status < 300 ? undefined : `HTTP ${r.status}`),
      (e: Error) => e.message,
    )
    if (why !== undefined) ctx.out.note(`warning: the refresh token could not be revoked (${why}); forgetting it here anyway`)
    await ctx.app.stores.delete(ctx.profile)
    ctx.app.session = undefined
    ctx.out.nothing()
  }
}
