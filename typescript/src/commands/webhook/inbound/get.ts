import { idArg, LingaraCommand, type Parsed, text } from '../../../lib/base.js'
import type { Ctx } from '../../../lib/ctx.js'
import { printGet, WEBHOOK_INBOUND } from '../../../lib/platform/objects.js'

export default class WebhookInboundGet extends LingaraCommand {
  static override summary = 'Show one inbound webhook.'
  static override args = idArg

  static override async exec(ctx: Ctx, { args }: Parsed): Promise<void> {
    await printGet(ctx, WEBHOOK_INBOUND, text(args.id))
  }
}
