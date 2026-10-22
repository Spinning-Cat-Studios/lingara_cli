// `embed create` (D2): the metered, confidential client first (its body never
// names `embed:mint`, the embed's projection adds it), its secret, then the embed.
import { Flags } from '@oclif/core'

import { LingaraCommand, many, type Parsed, text } from '../../lib/base.js'
import type { Ctx } from '../../lib/ctx.js'
import { Creation } from '../../lib/platform/creation.js'
import { EMBED, OAUTH_CLIENT, str } from '../../lib/platform/objects.js'

export default class EmbedCreate extends LingaraCommand {
  static override summary = "Create an embed and its metered OAuth client, and show the client's secret once."
  static override flags = {
    name: Flags.string({ required: true, summary: "The embed's name" }),
    origin: Flags.string({ multiple: true, helpValue: 'origin', summary: 'A web origin allowed to use it (up to 10)' }),
    scope: Flags.string({ multiple: true, helpValue: 'scope', summary: 'A scope beside embed:mint' }),
  }

  static override async exec(ctx: Ctx, { flags }: Parsed): Promise<void> {
    const name = text(flags.name)
    const made = new Creation()
    await made.run(ctx, async () => {
      const clientBody = { client_type: 'confidential', billing_mode: 'metered', allowed_scopes: many(flags.scope) }
      const client = str((await made.object(ctx, OAUTH_CLIENT, name, undefined, clientBody)).id)
      await made.mint(ctx, OAUTH_CLIENT, client)
      return [EMBED, str((await made.object(ctx, EMBED, name, client, { origins: many(flags.origin) })).id)]
    })
  }
}
