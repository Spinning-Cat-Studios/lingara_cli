// `oauth_client delete` (D5): the children 16.10.26v §7's cascade deletes,
// counted by kind from the one read.
import { message } from '../../lib/assets.js'
import { deleteFlags, deleteInput, idArg, LingaraCommand, type Parsed } from '../../lib/base.js'
import type { Ctx } from '../../lib/ctx.js'
import { deleteObject } from '../../lib/platform/deletes.js'
import { OAUTH_CLIENT, type Obj } from '../../lib/platform/objects.js'

export function clientConsequence(obj: Obj): string {
  const children = (obj.children as Obj[] | undefined) ?? []
  const count = (kinds: string[]): string => String(children.filter((c) => kinds.includes(String(c.kind))).length)
  return message('consequence_oauth_client', { apps: count(['app']), embeds: count(['embed']), webhooks: count(['webhook_in', 'webhook_out']) })
}

export default class OauthClientDelete extends LingaraCommand {
  static override summary = 'Delete an OAuth client and everything under it, or one version.'
  static override aliases = ['client:delete']
  static override args = idArg
  static override flags = deleteFlags

  static override async exec(ctx: Ctx, input: Parsed): Promise<void> {
    await deleteObject(ctx, OAUTH_CLIENT, deleteInput(input), clientConsequence)
  }
}
