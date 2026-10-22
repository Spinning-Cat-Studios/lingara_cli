import { message } from '../../lib/assets.js'
import { deleteFlags, deleteInput, idArg, LingaraCommand, type Parsed } from '../../lib/base.js'
import type { Ctx } from '../../lib/ctx.js'
import { deleteObject, parent } from '../../lib/platform/deletes.js'
import { EMBED } from '../../lib/platform/objects.js'

export default class EmbedDelete extends LingaraCommand {
  static override summary = 'Delete an embed and its OAuth client, or one version.'
  static override args = idArg
  static override flags = deleteFlags

  static override async exec(ctx: Ctx, input: Parsed): Promise<void> {
    await deleteObject(ctx, EMBED, deleteInput(input), (o) => message('consequence_embed', { client: parent(o) }))
  }
}
