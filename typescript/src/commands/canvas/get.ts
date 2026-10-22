import { idArg, LingaraCommand, type Parsed, text } from '../../lib/base.js'
import { getCanvas } from '../../lib/canvas/apply.js'
import type { Ctx } from '../../lib/ctx.js'

export default class CanvasGet extends LingaraCommand {
  static override summary = 'Show one canvas.'
  static override args = idArg

  static override async exec(ctx: Ctx, { args }: Parsed): Promise<void> {
    ctx.out.value(await getCanvas(ctx, text(args.id)))
  }
}
