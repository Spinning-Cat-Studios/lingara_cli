// `oauth_client secret delete` (D1, D5): rotation's second call. A secret has
// no read of its own, so the prompt names the client's.
import { Args } from '@oclif/core'

import { message } from '../../../lib/assets.js'
import { confirmFlags, LingaraCommand, opt, type Parsed, text } from '../../../lib/base.js'
import { confirmDestructive } from '../../../lib/confirm.js'
import type { Ctx } from '../../../lib/ctx.js'
import { promptFor } from '../../../lib/platform/deletes.js'
import { get, OAUTH_CLIENT } from '../../../lib/platform/objects.js'
import { object } from '../../../lib/routes.js'

export default class OauthClientSecretDelete extends LingaraCommand {
  static override summary = 'Revoke a secret.'
  static override aliases = ['client:secret:delete']
  static override args = { client_id: Args.string({ required: true }), secret_id: Args.string({ required: true }) }
  static override flags = confirmFlags

  static override async exec(ctx: Ctx, { args, flags }: Parsed): Promise<void> {
    const [clientId, secretId] = [text(args.client_id), text(args.secret_id)]
    const read = async () => {
      const prompt = promptFor(OAUTH_CLIENT, await get(ctx, OAUTH_CLIENT, clientId), secretId, undefined, () => message('consequence_secret'))
      return { ...prompt, kind: 'secret', pinnedBy: 0 }
    }
    const email = await confirmDestructive(ctx, { yes: flags.yes === true, confirm: opt(flags.confirm) }, read)
    const route = object(OAUTH_CLIENT.word, 'secret_delete')
    await ctx.call({ route, vars: { id: clientId, secret_id: secretId }, body: { json: { confirm: email } } })
    ctx.out.nothing()
  }
}
