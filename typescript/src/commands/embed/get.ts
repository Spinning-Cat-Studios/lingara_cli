import { idArg, LingaraCommand, type Parsed, text } from '../../lib/base.js'
import type { Ctx } from '../../lib/ctx.js'
import { EMBED, printGet } from '../../lib/platform/objects.js'

export default class EmbedGet extends LingaraCommand {
  static override summary = 'Show one embed.'
  static override args = idArg

  static override async exec(ctx: Ctx, { args }: Parsed): Promise<void> {
    await printGet(ctx, EMBED, text(args.id))
  }
}
