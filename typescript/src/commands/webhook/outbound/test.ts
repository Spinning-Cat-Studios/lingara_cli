import { idArg, LingaraCommand, type Parsed, text } from '../../../lib/base.js'
import type { Ctx } from '../../../lib/ctx.js'
import { objectAction } from './update.js'

export default class WebhookOutboundTest extends LingaraCommand {
  static override summary = 'Send a test delivery.'
  static override args = idArg

  static override async exec(ctx: Ctx, { args }: Parsed): Promise<void> {
    ctx.out.value(await objectAction(ctx, 'test', text(args.id)))
  }
}
