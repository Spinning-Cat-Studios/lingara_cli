// `apps update` (ADR 16.10.26ad D2; 16.10.26ae D6). One dispatch point on
// `--manifest`: with it, `apps update <id> --manifest <path>` makes a new
// version from the file; without it, the project's manifest goes to the dev
// version (`projectUpdate.ts`). A quick-tunnel or `dev.invalid` render URL in
// the file is refused before any request (D6 step 3's guard, D10 row 15).
import { Args, Flags } from '@oclif/core'

import { configFlag, LingaraCommand, opt, pathFlag, type Parsed } from '../../lib/base.js'
import type { Ctx } from '../../lib/ctx.js'
import { ephemeral } from '../../lib/dev/manifest.js'
import { Exit, fromMessage, usage } from '../../lib/fail.js'
import { APP, bodyUpdate } from '../../lib/platform/objects.js'
import { manifestBody } from './create.js'
import { projectUpdate } from './projectUpdate.js'

async function fromFile(ctx: Ctx, id: string | undefined, path: string): Promise<void> {
  if (id === undefined) throw usage("apps update --manifest needs the app's id")
  const { manifest } = manifestBody(path)
  const renderUrl = (manifest as { render_url?: unknown } | null)?.render_url
  if (ephemeral(typeof renderUrl === 'string' ? renderUrl : '')) throw fromMessage(Exit.renderUrlEphemeral, 'render_url_ephemeral', { file: path })
  ctx.out.value(await bodyUpdate(ctx, APP, id, (body) => (body.manifest = manifest)))
}

export default class AppsUpdate extends LingaraCommand {
  static override summary =
    "Without --manifest, push the project's manifest to its dev version (--version cuts a draft); with an id and --manifest, make a new version from a file."
  static override args = { id: Args.string() }
  static override flags = {
    manifest: pathFlag({ helpValue: 'path', summary: "The app's manifest file: makes a new version", exclusive: ['version'] }),
    version: Flags.string({ helpValue: 'semver', summary: "Cut a draft version from the project's manifest" }),
    ...configFlag,
  }

  static override async exec(ctx: Ctx, { args, flags }: Parsed): Promise<void> {
    const manifest = opt(flags.manifest)
    if (manifest !== undefined) return fromFile(ctx, opt(args.id), manifest)
    return projectUpdate(ctx, opt(args.id), opt(flags.version), opt(flags.config))
  }
}
