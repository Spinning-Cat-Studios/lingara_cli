// `whoami` (ADR 16.10.26ad D3): `EMAIL  DEVELOPER  SCOPES  PROFILE`, the
// scopes from the token response.
import { LingaraCommand } from '../lib/base.js'
import type { Ctx } from '../lib/ctx.js'
import { cell, render } from '../lib/output.js'
import { fixed } from '../lib/routes.js'

export default class Whoami extends LingaraCommand {
  static override summary = 'Show the developer the credentials belong to.'

  static override async exec(ctx: Ctx): Promise<void> {
    const me = (await ctx.call({ route: fixed('developer.me') })) as Record<string, unknown>
    if (ctx.out.json) {
      ctx.out.jsonValue(me)
      return
    }
    const row = [cell(me.email), cell(me.developer_id), ctx.app.session?.scopes ?? '', ctx.profile]
    ctx.out.print(render(['EMAIL', 'DEVELOPER', 'SCOPES', 'PROFILE'], [row]))
  }
}
