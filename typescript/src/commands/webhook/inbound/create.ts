// `webhook inbound create` (D2): a confidential client (its body never names
// `events:write`), its secret, then the `webhook_in` object. No `--event`
// omits `event_types`, which 16.10.26v §5 projects as every inbound type.
import { Flags } from '@oclif/core'

import { LingaraCommand, many, type Parsed, text } from '../../../lib/base.js'
import type { Ctx } from '../../../lib/ctx.js'
import { Creation } from '../../../lib/platform/creation.js'
import { OAUTH_CLIENT, str, WEBHOOK_INBOUND } from '../../../lib/platform/objects.js'

export default class WebhookInboundCreate extends LingaraCommand {
  static override summary = "Create an inbound webhook and its client, and show the client's secret once."
  static override flags = {
    name: Flags.string({ required: true, summary: "The webhook's name" }),
    event: Flags.string({ multiple: true, helpValue: 'type', summary: 'An inbound event type it accepts; absent, every one' }),
  }

  static override async exec(ctx: Ctx, { flags }: Parsed): Promise<void> {
    const [name, events] = [text(flags.name), many(flags.event)]
    const made = new Creation()
    const hook = await made.run(ctx, async () => {
      const client = str((await made.object(ctx, OAUTH_CLIENT, name, undefined, { client_type: 'confidential', allowed_scopes: [] })).id)
      await made.mint(ctx, OAUTH_CLIENT, client)
      const body = events.length === 0 ? {} : { event_types: events }
      return [WEBHOOK_INBOUND, str((await made.object(ctx, WEBHOOK_INBOUND, name, client, body)).id)]
    })
    const ingest = `${ctx.app.http.bases.api.replace(/\/+$/, '')}/v1/events`
    ctx.out.note(`Senders POST to ${ingest} as client ${str(hook.client_id) || '-'}, with its secret and an Idempotency-Key header.`)
  }
}
