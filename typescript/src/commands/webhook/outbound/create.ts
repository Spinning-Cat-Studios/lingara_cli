// `webhook outbound create` (D2): create, publish, mint the signing secret
// (revealed once), read. The verb takes no name, so the object is named by
// its URL, cut to the 80 characters a platform object name may hold.
import { Flags } from '@oclif/core'

import { LingaraCommand, many, type Parsed, text } from '../../../lib/base.js'
import type { Ctx } from '../../../lib/ctx.js'
import { Creation } from '../../../lib/platform/creation.js'
import { type Obj, str, WEBHOOK_OUTBOUND } from '../../../lib/platform/objects.js'

export function createOutbound(ctx: Ctx, client: string, url: string, events: string[]): Promise<Obj> {
  const made = new Creation()
  return made.run(ctx, async () => {
    const hook = await made.object(ctx, WEBHOOK_OUTBOUND, [...url].slice(0, 80).join(''), client, { url, event_types: events })
    await made.mint(ctx, WEBHOOK_OUTBOUND, str(hook.id))
    return [WEBHOOK_OUTBOUND, str(hook.id)]
  })
}

export default class WebhookOutboundCreate extends LingaraCommand {
  static override summary = 'Create an outbound webhook, and show its signing secret once.'
  static override flags = {
    client: Flags.string({ required: true, helpValue: 'id', summary: 'The OAuth client it belongs to' }),
    url: Flags.string({ required: true, helpValue: 'url', summary: 'The endpoint deliveries go to' }),
    event: Flags.string({ required: true, multiple: true, helpValue: 'type', summary: 'An event type to deliver' }),
  }

  static override async exec(ctx: Ctx, { flags }: Parsed): Promise<void> {
    await createOutbound(ctx, text(flags.client), text(flags.url), many(flags.event))
  }
}
