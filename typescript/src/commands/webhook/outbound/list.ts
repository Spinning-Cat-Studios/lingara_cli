import { LingaraCommand } from '../../../lib/base.js'
import type { Ctx } from '../../../lib/ctx.js'
import { printList, WEBHOOK_OUTBOUND } from '../../../lib/platform/objects.js'

export default class WebhookOutboundList extends LingaraCommand {
  static override summary = 'List your outbound webhooks.'

  static override async exec(ctx: Ctx): Promise<void> {
    await printList(ctx, WEBHOOK_OUTBOUND, [['ID', 'id'], ['CLIENT', 'parent_id'], ['URL', 'url'], ['EVENTS', 'event_types'], ['ENABLED', 'enabled']])
  }
}
