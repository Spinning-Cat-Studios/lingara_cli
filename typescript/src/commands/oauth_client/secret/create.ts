// `oauth_client secret create` (D1): rotation's first call; the old secret stays live.
import { Args } from '@oclif/core'

import { LingaraCommand, type Parsed, text } from '../../../lib/base.js'
import type { Ctx } from '../../../lib/ctx.js'
import { SHOWN_ONCE } from '../../../lib/platform/creation.js'
import { OAUTH_CLIENT, secretCreate } from '../../../lib/platform/objects.js'

export default class OauthClientSecretCreate extends LingaraCommand {
  static override summary = 'Mint a new secret, shown once. The old one stays live.'
  static override aliases = ['client:secret:create']
  static override args = { client_id: Args.string({ required: true }) }

  static override async exec(ctx: Ctx, { args }: Parsed): Promise<void> {
    ctx.out.value(await secretCreate(ctx, OAUTH_CLIENT, text(args.client_id)))
    ctx.out.note(SHOWN_ONCE)
  }
}
