// `oauth_client create` (ADR 16.10.26ad D1, D2). `--ci` is confidential with
// the four scopes; whether the client may be one is the server's predicate.
import { Flags } from '@oclif/core'

import { SCOPES } from '../../lib/auth/tokens.js'
import { LingaraCommand, many, type Parsed, text } from '../../lib/base.js'
import type { Ctx } from '../../lib/ctx.js'
import { Creation } from '../../lib/platform/creation.js'
import { OAUTH_CLIENT, type Obj, str } from '../../lib/platform/objects.js'

export interface ClientCreate {
  name: string
  scope: string[]
  type: string
  billingMode: string
  redirectUri: string[]
  ci: boolean
}

/** The client's version body (16.10.26v §1). */
export const clientBody = (a: ClientCreate): Obj => ({
  client_type: a.ci ? 'confidential' : a.type,
  allowed_scopes: a.ci ? SCOPES.split(' ') : a.scope,
  billing_mode: a.billingMode,
  redirect_uris: a.redirectUri,
})

/** Create, publish, mint the confidential client's one-time secret, read. */
export function createClient(ctx: Ctx, a: ClientCreate): Promise<Obj> {
  const made = new Creation()
  return made.run(ctx, async () => {
    const client = await made.object(ctx, OAUTH_CLIENT, a.name, undefined, clientBody(a))
    if (a.ci || a.type === 'confidential') await made.mint(ctx, OAUTH_CLIENT, str(client.id))
    return [OAUTH_CLIENT, str(client.id)]
  })
}

export default class OauthClientCreate extends LingaraCommand {
  static override summary = "Create an OAuth client; a confidential one's secret is shown once."
  static override aliases = ['client:create']
  static override flags = {
    name: Flags.string({ required: true, summary: "The client's name" }),
    scope: Flags.string({ multiple: true, helpValue: 'scope', summary: 'A scope the client may ask for' }),
    type: Flags.option({ options: ['confidential', 'public'] as const, default: 'confidential', summary: 'Whether the client keeps a secret' })(),
    'billing-mode': Flags.option({ options: ['allowance', 'metered'] as const, default: 'allowance', helpValue: 'mode', summary: 'How its usage is billed' })(),
    'redirect-uri': Flags.string({ multiple: true, helpValue: 'uri', summary: 'A redirect URI it may use' }),
    ci: Flags.boolean({ exclusive: ['type', 'scope'], summary: 'A CI client: confidential, holding the four platform and canvas scopes' }),
  }

  static override async exec(ctx: Ctx, { flags }: Parsed): Promise<void> {
    const f = { name: text(flags.name), scope: many(flags.scope), type: text(flags.type), billingMode: text(flags['billing-mode']) }
    await createClient(ctx, { ...f, redirectUri: many(flags['redirect-uri']), ci: flags.ci === true })
  }
}
