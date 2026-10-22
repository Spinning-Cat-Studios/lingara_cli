// `canvas delete` (D1, D5): `canvas.lookup` turns the builder's id into the
// platform object's; the prompt reads that object; the delete is
// `/platform/objects`', with the typed email, as for every other kind.
import { message } from '../../lib/assets.js'
import { deleteFlags, deleteInput, idArg, LingaraCommand, type Parsed } from '../../lib/base.js'
import { confirmDestructive } from '../../lib/confirm.js'
import type { Ctx } from '../../lib/ctx.js'
import { Exit, Fail } from '../../lib/fail.js'
import { promptFor } from '../../lib/platform/deletes.js'
import { CANVAS, destroy, type Obj, str } from '../../lib/platform/objects.js'
import { fixed, objectNamed } from '../../lib/routes.js'

/** The `canvas` object behind a `/v1/canvases` id (16.10.26v §8). */
async function lookup(ctx: Ctx, canvasId: string): Promise<string> {
  const page = (await ctx.call({ route: fixed('canvas.lookup'), query: [['kind', 'canvas'], ['runtime_id', canvasId]] })) as Obj
  const found = str(((page.items as Obj[] | undefined) ?? [])[0]?.id)
  if (found === '') throw new Fail(Exit.notFound, 'object_not_found', `no canvas ${canvasId}`)
  return found
}

export default class CanvasDelete extends LingaraCommand {
  static override summary = 'Delete a canvas, or one version. The objects it made stay.'
  static override args = idArg
  static override flags = deleteFlags

  static override async exec(ctx: Ctx, input: Parsed): Promise<void> {
    const { id, version, confirm } = deleteInput(input)
    let platformId: string | undefined
    const email = await confirmDestructive(ctx, confirm, async () => {
      platformId = await lookup(ctx, id)
      const obj = (await ctx.call({ route: objectNamed('canvas.object_get', 'get'), vars: { id: platformId } })) as Obj
      return promptFor(CANVAS, obj, id, version, () => message('consequence_canvas'))
    })
    await destroy(ctx, CANVAS, platformId ?? (await lookup(ctx, id)), version, email)
    ctx.out.nothing()
  }
}
