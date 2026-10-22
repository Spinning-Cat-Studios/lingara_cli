import { Flags } from '@oclif/core'

import { idArg, LingaraCommand, type Parsed, text } from '../../lib/base.js'
import type { Ctx } from '../../lib/ctx.js'
import { fixed } from '../../lib/routes.js'

export default class CanvasUpdate extends LingaraCommand {
  static override summary = 'Rename a canvas.'
  static override args = idArg
  static override flags = { name: Flags.string({ required: true, summary: 'The new name' }) }

  static override async exec(ctx: Ctx, { args, flags }: Parsed): Promise<void> {
    ctx.out.value(await ctx.call({ route: fixed('canvas.rename'), vars: { id: text(args.id) }, body: { json: { name: text(flags.name) } } }))
  }
}
