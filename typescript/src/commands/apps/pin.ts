// `apps pin <id|name>` (ADR 16.10.26ae D4): a local row, never a platform write.
import { Args } from '@oclif/core'

import { configFlag, LingaraCommand, opt, pathFlag, type Parsed, text } from '../../lib/base.js'
import type { Ctx } from '../../lib/ctx.js'
import { pin } from './local.js'

export default class AppsPin extends LingaraCommand {
  static override summary = 'Add an app to platform.toml (and its example), by id or by name.'
  static override args = { app: Args.string({ required: true }) }
  static override flags = {
    path: pathFlag({ helpValue: 'dir', summary: 'The project directory; needed for a name' }),
    ...configFlag,
  }

  static override async exec(ctx: Ctx, { args, flags }: Parsed): Promise<void> {
    await pin(ctx, text(args.app), opt(flags.path), opt(flags.config))
  }
}
