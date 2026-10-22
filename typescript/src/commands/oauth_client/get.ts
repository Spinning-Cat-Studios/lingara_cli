import { idArg, LingaraCommand, type Parsed, text } from '../../lib/base.js'
import type { Ctx } from '../../lib/ctx.js'
import { OAUTH_CLIENT, printGet } from '../../lib/platform/objects.js'

export default class OauthClientGet extends LingaraCommand {
  static override summary = 'Show one OAuth client.'
  static override aliases = ['client:get']
  static override args = idArg

  static override async exec(ctx: Ctx, { args }: Parsed): Promise<void> {
    await printGet(ctx, OAUTH_CLIENT, text(args.id))
  }
}
