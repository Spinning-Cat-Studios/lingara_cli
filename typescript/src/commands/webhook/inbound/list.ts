import { LingaraCommand } from '../../../lib/base.js'
import type { Ctx } from '../../../lib/ctx.js'
import { printList, WEBHOOK_INBOUND } from '../../../lib/platform/objects.js'

export default class WebhookInboundList extends LingaraCommand {
  static override summary = 'List your inbound webhooks.'

  static override async exec(ctx: Ctx): Promise<void> {
    await printList(ctx, WEBHOOK_INBOUND, [['ID', 'id'], ['NAME', 'name'], ['CLIENT', 'parent_id'], ['EVENTS', 'event_types']])
  }
}
