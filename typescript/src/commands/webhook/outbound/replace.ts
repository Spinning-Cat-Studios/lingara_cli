// `webhook outbound replace` (D1, D5): a new endpoint with a new signing
// secret, then the old one deleted. Both prompts, or the `--confirm` check,
// complete **before** the new endpoint's first write. If the create lands and
// the delete is refused, the new secret is still revealed and both ids named.
import { Flags } from '@oclif/core'

import { message } from '../../../lib/assets.js'
import { confirmFlags, idArg, LingaraCommand, many, opt, type Parsed, text } from '../../../lib/base.js'
import { confirmDestructive } from '../../../lib/confirm.js'
import type { Ctx } from '../../../lib/ctx.js'
import { Fail } from '../../../lib/fail.js'
import { promptFor } from '../../../lib/platform/deletes.js'
import { destroy, get, type Obj, str, versionOp, WEBHOOK_OUTBOUND } from '../../../lib/platform/objects.js'
import { createOutbound } from './create.js'

/** The old object and its serving version's body: where the new endpoint's client and any omitted flag come from. */
async function lookups(ctx: Ctx, id: string): Promise<[Obj, Obj]> {
  const old = await get(ctx, WEBHOOK_OUTBOUND, id)
  const versions = (old.versions as Obj[] | undefined) ?? []
  const semver = str(old.serving_version) || str(versions[0]?.semver)
  const version = await versionOp(ctx, WEBHOOK_OUTBOUND, 'version_get', id, semver)
  return [old, (version.body as Obj | undefined) ?? {}]
}

export default class WebhookOutboundReplace extends LingaraCommand {
  static override summary = 'Start again: a new endpoint with a new signing secret, then the old one deleted.'
  static override args = idArg
  static override flags = {
    url: Flags.string({ helpValue: 'url', summary: "The new endpoint; absent, the old one's" }),
    event: Flags.string({ multiple: true, helpValue: 'type', summary: "The new event types; absent, the old one's" }),
    ...confirmFlags,
  }

  static override async exec(ctx: Ctx, { args, flags }: Parsed): Promise<void> {
    const id = text(args.id)
    let looked: [Obj, Obj] | undefined
    const read = async () => {
      looked = await lookups(ctx, id)
      return promptFor(WEBHOOK_OUTBOUND, looked[0], id, undefined, () => message('consequence_replace'))
    }
    const email = await confirmDestructive(ctx, { yes: flags.yes === true, confirm: opt(flags.confirm) }, read)
    const [old, body] = looked ?? (await lookups(ctx, id))
    const events = many(flags.event).length > 0 ? many(flags.event) : many(body.event_types)
    const created = await createOutbound(ctx, str(old.parent_id), opt(flags.url) ?? str(body.url), events)
    await destroy(ctx, WEBHOOK_OUTBOUND, id, undefined, email).catch((e: unknown) => {
      if (e instanceof Fail) {
        e.made = [{ kind: 'webhook_outbound', id: str(created.id) }]
        e.hint(`the old endpoint ${id} still delivers; delete it with \`lingara webhook outbound delete ${id}\``)
      }
      throw e
    })
  }
}
