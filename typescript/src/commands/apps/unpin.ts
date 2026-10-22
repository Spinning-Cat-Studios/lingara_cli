// `apps unpin <name>` (ADR 16.10.26ae D4): platform.toml only; the example changes by review.
import { Args } from '@oclif/core'

import { configFlag, LingaraCommand, opt, type Parsed, text } from '../../lib/base.js'
import type { Ctx } from '../../lib/ctx.js'
import { unpin } from './local.js'

export default class AppsUnpin extends LingaraCommand {
  static override summary = "Remove an app's row from platform.toml; the example changes by review."
  static override args = { name: Args.string({ required: true }) }
  static override flags = { ...configFlag }

  static override async exec(ctx: Ctx, { args, flags }: Parsed): Promise<void> {
    unpin(ctx, text(args.name), opt(flags.config))
  }
}
