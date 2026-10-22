import { message } from '../../lib/assets.js'
import { deleteFlags, deleteInput, idArg, LingaraCommand, type Parsed } from '../../lib/base.js'
import type { Ctx } from '../../lib/ctx.js'
import { deleteObject, parent } from '../../lib/platform/deletes.js'
import { APP } from '../../lib/platform/objects.js'

export default class AppsDelete extends LingaraCommand {
  static override summary = 'Delete an app, or one of its versions.'
  static override args = idArg
  static override flags = deleteFlags

  static override async exec(ctx: Ctx, input: Parsed): Promise<void> {
    await deleteObject(ctx, APP, deleteInput(input), (o) => message('consequence_app', { client: parent(o) }))
  }
}
