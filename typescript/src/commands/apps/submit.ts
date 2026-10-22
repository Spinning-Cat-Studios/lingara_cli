// `apps submit` (D1): `{"visibility": "public"}`; review itself is the operator's, in the portal.
import { Flags } from '@oclif/core'

import { idArg, LingaraCommand, type Parsed, text } from '../../lib/base.js'
import type { Ctx } from '../../lib/ctx.js'
import { APP } from '../../lib/platform/objects.js'
import { object } from '../../lib/routes.js'

export default class AppsSubmit extends LingaraCommand {
  static override summary = 'Submit a version of an app for review, to make it public.'
  static override args = idArg
  static override flags = {
    version: Flags.string({ required: true, helpValue: 'semver', summary: 'The version to submit' }),
  }

  static override async exec(ctx: Ctx, { args, flags }: Parsed): Promise<void> {
    const vars = { id: text(args.id), semver: text(flags.version) }
    ctx.out.value(await ctx.call({ route: object(APP.word, 'submit'), vars, body: { json: { visibility: 'public' } } }))
  }
}
