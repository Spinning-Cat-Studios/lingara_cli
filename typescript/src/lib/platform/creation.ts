// A create that may take several calls (ADR 16.10.26ad D1, D2). It ends with
// one `<word>.get`, printed with any secret it minted. Failing part-way, it
// still reveals that secret on stdout and names every object it made on
// stderr, then exits with the failing call's code. Nothing is rolled back.
import type { Ctx } from '../ctx.js'
import { Fail, type Made } from '../fail.js'
import { cell, fields, render } from '../output.js'
import { create, get, type Kind, type Obj, publishIfPrivate, secretCreate, str } from './objects.js'

export const SHOWN_ONCE = 'The secret is shown once: store it now. It cannot be read again.'

export class Creation {
  made: Made[] = []
  /** `{id, value}`, the D6 member a create's JSON gains. */
  secret?: { id: unknown; value: unknown }

  /** One object: created, named as made, then published when private. */
  async object(ctx: Ctx, k: Kind, name: string, parent: string | undefined, body: unknown): Promise<Obj> {
    const created = await create(ctx, k, name, parent, body)
    this.made.push({ kind: k.word, id: str(created.id) })
    await publishIfPrivate(ctx, k, created, str((created.version as Obj | undefined)?.semver))
    return created
  }

  /** The one-time secret. `409 object_projection_pending`: the client is still publishing (16.10.26v §5). */
  async mint(ctx: Ctx, k: Kind, id: string): Promise<void> {
    try {
      const minted = await secretCreate(ctx, k, id)
      this.secret = { id: minted.secret_id, value: minted.secret }
    } catch (e) {
      if (e instanceof Fail && e.code === 'object_projection_pending') e.hint(`the client is still publishing; run \`lingara client secret create ${id}\` in a moment`)
      throw e
    }
  }

  /** Runs `steps`, then the final read and its printing, or the failure with what was made. */
  async run(ctx: Ctx, steps: () => Promise<[Kind, string]>): Promise<Obj> {
    let obj: Obj
    try {
      const [k, id] = await steps()
      obj = await get(ctx, k, id)
    } catch (e) {
      this.print(ctx, undefined)
      if (e instanceof Fail) e.made = this.made
      throw e
    }
    this.print(ctx, obj)
    return obj
  }

  private print(ctx: Ctx, obj: Obj | undefined): void {
    if (obj === undefined && this.secret === undefined) return
    if (ctx.out.json) {
      ctx.out.jsonValue({ ...(obj ?? {}), ...(this.secret === undefined ? {} : { secret: this.secret }) })
    } else {
      const rows = obj === undefined ? [] : fields(obj)
      if (this.secret !== undefined) rows.push(['SECRET', cell(this.secret.value)])
      ctx.out.print(render(['FIELD', 'VALUE'], rows))
    }
    if (this.secret !== undefined) ctx.out.note(SHOWN_ONCE)
  }
}
