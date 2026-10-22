import { message } from '../../../lib/assets.js'
import { deleteFlags, deleteInput, idArg, LingaraCommand, type Parsed } from '../../../lib/base.js'
import type { Ctx } from '../../../lib/ctx.js'
import { deleteObject } from '../../../lib/platform/deletes.js'
import { WEBHOOK_OUTBOUND } from '../../../lib/platform/objects.js'

export default class WebhookOutboundDelete extends LingaraCommand {
  static override summary = 'Delete an outbound webhook, or one version.'
  static override args = idArg
  static override flags = deleteFlags

  static override async exec(ctx: Ctx, input: Parsed): Promise<void> {
    await deleteObject(ctx, WEBHOOK_OUTBOUND, deleteInput(input), () => message('consequence_webhook_outbound'))
  }
}
