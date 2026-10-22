// `embed update` (D1): `--name` renames; `--origin` replaces the origins in a body update.
import { Flags } from '@oclif/core'

import { idArg, LingaraCommand, many, opt, type Parsed, text } from '../../lib/base.js'
import type { Ctx } from '../../lib/ctx.js'
import { usage } from '../../lib/fail.js'
import { bodyUpdate, EMBED, type Obj, rename } from '../../lib/platform/objects.js'

export default class EmbedUpdate extends LingaraCommand {
  static override summary = 'Rename an embed, or replace its origins.'
  static override args = idArg
  static override flags = {
    name: Flags.string({ summary: 'The new name' }),
    origin: Flags.string({ multiple: true, helpValue: 'origin', summary: 'Replaces the allowed origins' }),
  }

  static override async exec(ctx: Ctx, { args, flags }: Parsed): Promise<void> {
    const [id, origins] = [text(args.id), many(flags.origin)]
    let last: Obj | undefined
    if (opt(flags.name) !== undefined) last = await rename(ctx, EMBED, id, text(flags.name))
    if (origins.length > 0) last = await bodyUpdate(ctx, EMBED, id, (body) => (body.origins = origins))
    if (last === undefined) throw usage('nothing to update: pass --name or --origin')
    ctx.out.value(last)
  }
}
