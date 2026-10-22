// `webhook outbound update` (D1): `--url`/`--event` are a body update that
// keeps the endpoint and its signing secret; `--enable`/`--disable` follow it
// on the object-level route, never version body.
import { Flags } from '@oclif/core'

import { idArg, LingaraCommand, many, opt, type Parsed, text } from '../../../lib/base.js'
import type { Ctx } from '../../../lib/ctx.js'
import { usage } from '../../../lib/fail.js'
import { bodyUpdate, type Obj, WEBHOOK_OUTBOUND } from '../../../lib/platform/objects.js'
import { object, type Op } from '../../../lib/routes.js'

export const objectAction = async (ctx: Ctx, op: Op, id: string): Promise<Obj> =>
  (await ctx.call({ route: object(WEBHOOK_OUTBOUND.word, op), vars: { id } })) as Obj

export default class WebhookOutboundUpdate extends LingaraCommand {
  static override summary = "Change an outbound webhook's URL or event types, keeping its signing secret, or enable or disable it."
  static override args = idArg
  static override flags = {
    url: Flags.string({ helpValue: 'url', summary: 'The new endpoint' }),
    event: Flags.string({ multiple: true, helpValue: 'type', summary: 'Replaces the event types' }),
    enable: Flags.boolean({ exclusive: ['disable'], summary: 'Resume deliveries' }),
    disable: Flags.boolean({ summary: 'Pause deliveries' }),
  }

  static override async exec(ctx: Ctx, { args, flags }: Parsed): Promise<void> {
    const [id, url, events] = [text(args.id), opt(flags.url), many(flags.event)]
    let last: Obj | undefined
    if (url !== undefined || events.length > 0) {
      last = await bodyUpdate(ctx, WEBHOOK_OUTBOUND, id, (body) => {
        if (url !== undefined) body.url = url
        if (events.length > 0) body.event_types = events
      })
    }
    if (flags.enable === true || flags.disable === true) last = await objectAction(ctx, flags.enable === true ? 'enable' : 'disable', id)
    if (last === undefined) throw usage('nothing to update: pass --url, --event, --enable or --disable')
    ctx.out.value(last)
  }
}
