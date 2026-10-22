// `apps status [name]` (ADR 16.10.26ae D9): where the dev version renders from.
import { Args } from '@oclif/core'

import { configFlag, LingaraCommand, opt, type Parsed } from '../../lib/base.js'
import type { Ctx } from '../../lib/ctx.js'
import { status } from './local.js'

export default class AppsStatus extends LingaraCommand {
  static override summary = "Show where the app's dev version renders from: live, parked, or the serving version."
  static override args = { name: Args.string() }
  static override flags = { ...configFlag }

  static override async exec(ctx: Ctx, { args, flags }: Parsed): Promise<void> {
    await status(ctx, opt(args.name), opt(flags.config))
  }
}
