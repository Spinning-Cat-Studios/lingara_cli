import { message } from '../../../lib/assets.js'
import { deleteFlags, deleteInput, idArg, LingaraCommand, type Parsed } from '../../../lib/base.js'
import type { Ctx } from '../../../lib/ctx.js'
import { deleteObject, parent } from '../../../lib/platform/deletes.js'
import { WEBHOOK_INBOUND } from '../../../lib/platform/objects.js'

export default class WebhookInboundDelete extends LingaraCommand {
  static override summary = 'Delete an inbound webhook, or one version. Its client stays.'
  static override args = idArg
  static override flags = deleteFlags

  static override async exec(ctx: Ctx, input: Parsed): Promise<void> {
    await deleteObject(ctx, WEBHOOK_INBOUND, deleteInput(input), (o) => message('consequence_webhook_inbound', { client: parent(o) }))
  }
}
