import { LingaraCommand } from '../../lib/base.js'
import type { Ctx } from '../../lib/ctx.js'
import { OAUTH_CLIENT, printList } from '../../lib/platform/objects.js'

export default class OauthClientList extends LingaraCommand {
  static override summary = 'List your OAuth clients.'
  static override aliases = ['client:list']

  static override async exec(ctx: Ctx): Promise<void> {
    await printList(ctx, OAUTH_CLIENT, [['ID', 'id'], ['NAME', 'name'], ['TYPE', 'client_type'], ['SCOPES', 'allowed_scopes'], ['CREATED', 'created_at']])
  }
}
