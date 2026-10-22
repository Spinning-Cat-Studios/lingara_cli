import { LingaraCommand } from '../../lib/base.js'
import type { Ctx } from '../../lib/ctx.js'
import { EMBED, printList } from '../../lib/platform/objects.js'

export default class EmbedList extends LingaraCommand {
  static override summary = 'List your embeds.'

  static override async exec(ctx: Ctx): Promise<void> {
    await printList(ctx, EMBED, [['ID', 'id'], ['NAME', 'name'], ['CLIENT', 'parent_id'], ['ORIGINS', 'origins'], ['SERVING', 'serving_version']])
  }
}
