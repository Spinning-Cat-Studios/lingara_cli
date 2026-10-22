import { LingaraCommand } from '../../lib/base.js'
import type { Ctx } from '../../lib/ctx.js'
import { type Obj, pages } from '../../lib/platform/objects.js'
import { fixed } from '../../lib/routes.js'
import { builderHint } from './create.js'

export default class CanvasList extends LingaraCommand {
  static override summary = 'List your canvases.'

  static override async exec(ctx: Ctx): Promise<void> {
    const route = fixed('canvas.list')
    const items = await pages(async (cursor) => (await ctx.call({ route, query: cursor === undefined ? [] : [['cursor', cursor]] })) as Obj).catch(builderHint)
    ctx.out.list(items, [['ID', 'id'], ['NAME', 'name'], ['DRAFT', 'has_draft'], ['ACTIVE', 'active_revision'], ['UPDATED', 'updated_at']])
  }
}
