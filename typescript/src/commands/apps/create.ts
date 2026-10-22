// `apps create` (ADR 16.10.26ad D2; 16.10.26ae D1). One module with **one
// dispatch point**: with `--manifest` and `--client`, 16.10.26ad's
// registration path, untouched; with neither, 16.10.26ae's scaffold.
import { readFileSync } from 'node:fs'

import { Args, Flags } from '@oclif/core'

import { configFlag, LingaraCommand, many, opt, pathFlag, type Parsed, portFlag, text } from '../../lib/base.js'
import type { Ctx } from '../../lib/ctx.js'
import { usage } from '../../lib/fail.js'
import { Creation } from '../../lib/platform/creation.js'
import { APP, type Obj, str } from '../../lib/platform/objects.js'
import { scaffold } from './scaffold.js'

/** `--manifest <path>` is the file's JSON as the app body's `manifest`. */
export function manifestBody(path: string): Obj {
  let body: string
  try {
    body = readFileSync(path, 'utf8')
  } catch (e) {
    throw usage(`${path}: ${(e as Error).message}`)
  }
  try {
    return { manifest: JSON.parse(body) as unknown }
  } catch (e) {
    throw usage(`${path} is not JSON: ${(e as Error).message}`)
  }
}

/** The registration form: register the object, write no files. */
function register(ctx: Ctx, name: string, client: string, manifest: string): Promise<Obj> {
  const body = manifestBody(manifest)
  const made = new Creation()
  return made.run(ctx, async () => [APP, str((await made.object(ctx, APP, name, client, body)).id)])
}

const MANIFEST_CONFLICTS = ['lang', 'bare', 'dir', 'no-register', 'description', 'port', 'slot', 'context']

export default class AppsCreate extends LingaraCommand {
  static override summary = 'Scaffold an app project and register it; or, with --client and --manifest, register an app from a manifest file.'
  static override args = { name: Args.string({ required: true }) }
  static override flags = {
    client: Flags.string({ helpValue: 'id', summary: "The OAuth client's id (with --manifest)", exclusive: ['lang', 'bare', 'dir'] }),
    manifest: pathFlag({ helpValue: 'path', summary: "The app's manifest file (with --client)", exclusive: MANIFEST_CONFLICTS }),
    lang: Flags.string({ options: ['typescript', 'rust', 'go', 'java', 'kotlin', 'ruby', 'php'], helpValue: 'app-kit-lang', summary: "The app's language" }),
    bare: Flags.boolean({ summary: "The manifest as code in the app's language, and a handler on the kit's primitives" }),
    dir: pathFlag({ helpValue: 'path', summary: 'Where to write the project; default ./<name>' }),
    'no-register': Flags.boolean({ summary: 'Scaffold only; register later with apps update' }),
    description: Flags.string({ helpValue: 'text', summary: 'One sentence about the app' }),
    port: portFlag('The port the app listens on; default 8787'),
    slot: Flags.string({ options: ['home.side', 'plans.empty_detail'], multiple: true, helpValue: 'slot', summary: "Where the app's card may appear; default home.side" }),
    context: Flags.string({ multiple: true, helpValue: 'slice', summary: 'A context slice the app receives' }),
    ...configFlag,
  }

  static override async exec(ctx: Ctx, { args, flags }: Parsed): Promise<void> {
    const [manifest, client] = [opt(flags.manifest), opt(flags.client)]
    if (manifest !== undefined && client !== undefined) {
      await register(ctx, text(args.name), client, manifest)
      return
    }
    if (manifest !== undefined || client !== undefined) throw usage('--manifest and --client go together: both register an app from a file, neither scaffolds a project')
    await scaffold(ctx, {
      name: text(args.name),
      lang: opt(flags.lang) ?? 'typescript',
      bare: flags.bare === true,
      dir: opt(flags.dir),
      noRegister: flags['no-register'] === true,
      description: opt(flags.description),
      port: typeof flags.port === 'number' ? flags.port : undefined,
      slots: many(flags.slot),
      context: many(flags.context),
      config: opt(flags.config),
    })
  }
}
