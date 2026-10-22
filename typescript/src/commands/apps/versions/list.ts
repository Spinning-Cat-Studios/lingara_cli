// `apps versions list` (D1): every page, newest first; `next_cursor` is the last semver seen.
import { idArg, LingaraCommand, type Parsed, text } from '../../../lib/base.js'
import type { Ctx } from '../../../lib/ctx.js'
import { APP, type Obj, pages } from '../../../lib/platform/objects.js'
import { object } from '../../../lib/routes.js'

export default class AppsVersionsList extends LingaraCommand {
  static override summary = "List an app's versions."
  static override args = idArg

  static override async exec(ctx: Ctx, { args }: Parsed): Promise<void> {
    const route = object(APP.word, 'versions')
    const items = await pages(async (cursor) => (await ctx.call({ route, vars: { id: text(args.id) }, query: cursor === undefined ? [] : [['cursor', cursor]] })) as Obj)
    ctx.out.list(items, [['SEMVER', 'semver'], ['STATE', 'state'], ['STATE_AT', 'state_at'], ['REVIEWED', 'review_basis']])
  }
}
