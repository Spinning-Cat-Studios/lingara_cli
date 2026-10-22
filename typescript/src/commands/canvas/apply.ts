import { Flags } from '@oclif/core'

import { idArg, LingaraCommand, type Parsed, text } from '../../lib/base.js'
import { apply } from '../../lib/canvas/apply.js'
import type { Ctx } from '../../lib/ctx.js'

export default class CanvasApply extends LingaraCommand {
  static override summary = "Create the canvas's objects by running this CLI's own commands, then activate it."
  static override args = idArg
  static override flags = {
    'dry-run': Flags.boolean({ exclusive: ['print-commands'], summary: 'Print what each object would do, and write nothing' }),
    'print-commands': Flags.boolean({ summary: 'Print the commands apply would run, and write nothing' }),
  }

  static override async exec(ctx: Ctx, { args, flags }: Parsed): Promise<void> {
    await apply(ctx, text(args.id), { dryRun: flags['dry-run'] === true, printCommands: flags['print-commands'] === true })
  }
}
