import { idArg, LingaraCommand, type Parsed, text } from '../../lib/base.js'
import type { Ctx } from '../../lib/ctx.js'
import { APP, printGet } from '../../lib/platform/objects.js'

export default class AppsGet extends LingaraCommand {
  static override summary = 'Show one app.'
  static override args = idArg

  static override async exec(ctx: Ctx, { args }: Parsed): Promise<void> {
    await printGet(ctx, APP, text(args.id))
  }
}
