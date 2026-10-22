// `canvas draft` (D7): saves the definition at the draft's `draft_version`
// (`0`: a new draft). It creates nothing else; that is `apply`'s alone (B7).
import { idArg, LingaraCommand, type Parsed, pathFlag, text } from '../../lib/base.js'
import { getCanvas } from '../../lib/canvas/apply.js'
import type { Ctx } from '../../lib/ctx.js'
import type { Obj } from '../../lib/platform/objects.js'
import { fixed } from '../../lib/routes.js'
import { definitionFile } from './create.js'

export default class CanvasDraft extends LingaraCommand {
  static override summary = "Save a definition as the canvas's draft. Creates nothing else."
  static override args = idArg
  static override flags = {
    file: pathFlag({ required: true, helpValue: 'definition.json', summary: 'The definition' }),
  }

  static override async exec(ctx: Ctx, { args, flags }: Parsed): Promise<void> {
    const [id, definition] = [text(args.id), definitionFile(text(flags.file))]
    const draft = (await getCanvas(ctx, id)).draft as Obj | null | undefined
    const version = typeof draft?.draft_version === 'number' ? draft.draft_version : 0
    ctx.out.value(await ctx.call({ route: fixed('canvas.put_draft'), vars: { id }, body: { json: { definition, draft_version: version } } }))
  }
}
