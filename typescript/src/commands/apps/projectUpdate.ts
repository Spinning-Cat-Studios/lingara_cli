// `lingara apps update [name]` without `--manifest` (ADR 16.10.26ae D6): the
// project's manifest goes to the **dev version** (`PATCH …/dev`, never its
// `render_url`), or with `--version` becomes an immutable draft. A row with
// no id is registered first.
import type { Ctx } from '../../lib/ctx.js'
import { patchWithRetry } from '../../lib/dev/devApi.js'
import { ephemeral } from '../../lib/dev/manifest.js'
import { read, register, source, warn } from '../../lib/dev/project.js'
import { open, resolve } from '../../lib/dev/rows.js'
import { Exit, fromMessage } from '../../lib/fail.js'
import type { Obj } from '../../lib/platform/objects.js'
import { object } from '../../lib/routes.js'

/** Step 3: an immutable draft from the project's manifest; the server's lifecycle rules stand, as `refused`. */
const cut = async (ctx: Ctx, id: string, semver: string, manifest: Obj): Promise<Obj> =>
  (await ctx.call({ route: object('app', 'version_create'), vars: { id }, body: { json: { semver, body: { manifest } } } })) as Obj

export async function projectUpdate(ctx: Ctx, wanted: string | undefined, version: string | undefined, config: string | undefined): Promise<void> {
  const [toml, cwd] = open(ctx, config)
  const row = resolve(toml, wanted, cwd)
  const manifest = read(toml, row, cwd)
  const renderUrl = typeof manifest.render_url === 'string' ? manifest.render_url : ''
  if (version !== undefined && ephemeral(renderUrl)) {
    throw fromMessage(Exit.renderUrlEphemeral, 'render_url_ephemeral', { file: source(toml, row, cwd) })
  }
  const codes = warn(ctx, manifest)
  if (row.id === undefined) {
    await register(ctx, toml, row, manifest)
    return
  }
  const id = row.id
  const answer =
    version !== undefined ? await cut(ctx, id, version, manifest) : await patchWithRetry(ctx, id, (draftVersion) => ({ manifest, draft_version: draftVersion }))
  // The server's answer, with `warnings: [code]` under `--json`.
  ctx.out.value(ctx.out.json ? { ...answer, warnings: codes } : answer)
}
