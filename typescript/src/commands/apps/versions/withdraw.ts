import { Args } from '@oclif/core'

import { LingaraCommand, type Parsed, text } from '../../../lib/base.js'
import type { Ctx } from '../../../lib/ctx.js'
import { APP, versionOp } from '../../../lib/platform/objects.js'

export default class AppsVersionsWithdraw extends LingaraCommand {
  static override summary = 'Withdraw a version.'
  static override args = { id: Args.string({ required: true }), semver: Args.string({ required: true }) }

  static override async exec(ctx: Ctx, { args }: Parsed): Promise<void> {
    ctx.out.value(await versionOp(ctx, APP, 'withdraw', text(args.id), text(args.semver)))
  }
}
