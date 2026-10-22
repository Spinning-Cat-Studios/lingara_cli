// `webhook inbound update` (D1): `--name` renames; `--event` replaces the event types in a body update.
import { Flags } from '@oclif/core'

import { idArg, LingaraCommand, many, opt, type Parsed, text } from '../../../lib/base.js'
import type { Ctx } from '../../../lib/ctx.js'
import { usage } from '../../../lib/fail.js'
import { bodyUpdate, type Obj, rename, WEBHOOK_INBOUND } from '../../../lib/platform/objects.js'

export default class WebhookInboundUpdate extends LingaraCommand {
  static override summary = 'Rename an inbound webhook, or replace its event types.'
  static override args = idArg
  static override flags = {
    name: Flags.string({ summary: 'The new name' }),
    event: Flags.string({ multiple: true, helpValue: 'type', summary: 'Replaces the event types' }),
  }

  static override async exec(ctx: Ctx, { args, flags }: Parsed): Promise<void> {
    const [id, events] = [text(args.id), many(flags.event)]
    let last: Obj | undefined
    if (opt(flags.name) !== undefined) last = await rename(ctx, WEBHOOK_INBOUND, id, text(flags.name))
    if (events.length > 0) last = await bodyUpdate(ctx, WEBHOOK_INBOUND, id, (body) => (body.event_types = events))
    if (last === undefined) throw usage('nothing to update: pass --name or --event')
    ctx.out.value(last)
  }
}
