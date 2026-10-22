// `oauth_client update` (D1): `--name` is a rename; any other flag is a
// body update over the one editable version.
import { Flags } from '@oclif/core'

import { idArg, LingaraCommand, many, opt, type Parsed, text } from '../../lib/base.js'
import type { Ctx } from '../../lib/ctx.js'
import { usage } from '../../lib/fail.js'
import { bodyUpdate, OAUTH_CLIENT, type Obj, rename } from '../../lib/platform/objects.js'

export default class OauthClientUpdate extends LingaraCommand {
  static override summary = 'Rename an OAuth client, or replace its scopes, API version or redirect URIs.'
  static override aliases = ['client:update']
  static override args = idArg
  static override flags = {
    name: Flags.string({ summary: 'The new name' }),
    scope: Flags.string({ multiple: true, helpValue: 'scope', summary: 'Replaces the scopes' }),
    'api-version': Flags.string({ helpValue: 'version', summary: 'The API version it is pinned to' }),
    'redirect-uri': Flags.string({ multiple: true, helpValue: 'uri', summary: 'Replaces the redirect URIs' }),
  }

  static override async exec(ctx: Ctx, { args, flags }: Parsed): Promise<void> {
    const [id, scope, uris, apiVersion] = [text(args.id), many(flags.scope), many(flags['redirect-uri']), opt(flags['api-version'])]
    let last: Obj | undefined
    if (opt(flags.name) !== undefined) last = await rename(ctx, OAUTH_CLIENT, id, text(flags.name))
    if (scope.length > 0 || uris.length > 0 || apiVersion !== undefined) {
      last = await bodyUpdate(ctx, OAUTH_CLIENT, id, (body) => {
        if (scope.length > 0) body.allowed_scopes = scope
        if (apiVersion !== undefined) body.api_version = apiVersion
        if (uris.length > 0) body.redirect_uris = uris
      })
    }
    if (last === undefined) throw usage('nothing to update: pass --name, --scope, --api-version or --redirect-uri')
    ctx.out.value(last)
  }
}
