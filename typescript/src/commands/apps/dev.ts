// `lingara apps dev [name]` (ADR 16.10.26ae D7), steps 1 and 2: resolve the
// row and run every refusal offline, so a refused run sends no request; then
// register a row with no id (D6 step 4) and hand over to `lib/dev/devRun.ts`.
// `--reset` is here too: one `PATCH {render_url: null}` and nothing else.
import { readFileSync } from 'node:fs'
import { isAbsolute, join, relative } from 'node:path'

import { Args, Flags } from '@oclif/core'

import { projectDir, type PlatformToml, type Row } from '../../platformToml.js'
import { find } from '../../tunnel/cloudflared.js'
import { message } from '../../lib/assets.js'
import { configFlag, LingaraCommand, opt, pathFlag, type Parsed, portFlag } from '../../lib/base.js'
import type { Ctx } from '../../lib/ctx.js'
import { patchWithRetry } from '../../lib/dev/devApi.js'
import { session } from '../../lib/dev/devRun.js'
import type { Plan, Target } from '../../lib/dev/plan.js'
import { read, register } from '../../lib/dev/project.js'
import { open, resolve } from '../../lib/dev/rows.js'
import { authRequired, Exit, Fail, fromMessage, usage } from '../../lib/fail.js'
import { ignored } from '../../lib/project/git.js'
import { str } from '../../lib/platform/objects.js'

function ignoredOrRefuse(path: string, cwd: string): void {
  if (!ignored(path)) throw fromMessage(Exit.usage, 'path_not_ignored', { path: path.startsWith(`${cwd}/`) ? relative(cwd, path) : path })
}

/** `KEY=VALUE` lines; blank lines, `#` comments and an `export ` prefix are skipped. */
function envFile(path: string): [string, string][] {
  let body: string
  try {
    body = readFileSync(path, 'utf8')
  } catch (e) {
    throw usage(`${path}: ${(e as Error).message}`)
  }
  return body.split('\n').flatMap((raw) => {
    const line = raw.trim().replace(/^export /, '')
    const eq = line.indexOf('=')
    if (line.startsWith('#') || eq <= 0) return []
    const value = line.slice(eq + 1).trim()
    const unquoted = /^(["'])(.*)\1$/.exec(value)?.[2] ?? value
    return [[line.slice(0, eq).trim(), unquoted] as [string, string]]
  })
}

function target(ctx: Ctx, tunnel: string | undefined, renderUrl: string | undefined): Target {
  if (tunnel === 'none' && renderUrl !== undefined) return { fixed: renderUrl }
  if (tunnel === 'none') throw usage('--tunnel none needs --render-url')
  if (renderUrl !== undefined) throw usage('--render-url needs --tunnel none')
  return { cloudflared: find(ctx.app.env) }
}

type Flagged = Record<string, unknown>

/** Step 2: every refusal before the network. The session record is read, never refreshed; a refresh, if due, rides the first request. */
async function preflight(ctx: Ctx, flags: Flagged, toml: PlatformToml, row: Row, cwd: string): Promise<Plan> {
  if (row.start === undefined) throw usage(`${row.name}'s row has no start command`)
  const dir = projectDir(toml, row)
  const env: [string, string][] = Object.entries(row.env)
  if (row.env_file !== undefined) {
    const path = join(dir, row.env_file)
    ignoredOrRefuse(path, cwd)
    env.push(...envFile(path))
  }
  const written = opt(flags['write-env'])
  const writeEnv = written === undefined ? undefined : isAbsolute(written) ? written : join(cwd, written)
  if (writeEnv !== undefined) ignoredOrRefuse(writeEnv, cwd)
  const to = target(ctx, opt(flags.tunnel), opt(flags['render-url']))
  if (ctx.ciPair() === undefined && (await ctx.app.stores.load(ctx.profile)) === undefined) throw authRequired('not signed in: run `lingara login`')
  const port = typeof flags.port === 'number' ? flags.port : row.port
  const client = row.credentials.includes('client_credentials')
  const probe = ctx.app.env.get('LINGARA_TUNNEL_PROBE_URL')
  return { name: row.name, id: '', dir, port, start: row.start, renderPath: row.render_path ?? '/', target: to, takeOver: flags['take-over'] === true, client, env, writeEnv, showEnv: flags['show-env'] === true, probe }
}

/** `--reset`: the owner's install back on the serving version. A live session refuses it (`apps_dev_reset_held`). */
async function reset(ctx: Ctx, row: Row): Promise<void> {
  if (row.id === undefined) throw usage(`${row.name} is not registered yet: run lingara apps update ${row.name} first`)
  try {
    await patchWithRetry(ctx, row.id, (draftVersion) => ({ render_url: null, draft_version: draftVersion }))
  } catch (e) {
    if (e instanceof Fail && e.exit === Exit.devSessionHeld) throw fromMessage(Exit.devSessionHeld, 'apps_dev_reset_held', { name: row.name })
    throw e
  }
  ctx.out.note(message('apps_dev_reset', { name: row.name }))
  ctx.out.nothing()
}

const RESET_CONFLICTS = ['port', 'tunnel', 'render-url', 'take-over', 'write-env', 'show-env']

export default class AppsDev extends LingaraCommand {
  static override summary = 'Run the app behind a tunnel and point your private dev version at it, until Ctrl+C.'
  static override args = { name: Args.string() }
  static override flags = {
    port: portFlag("The port the app listens on; default the row's"),
    tunnel: Flags.string({ options: ['cloudflared', 'none'], summary: 'The tunnel to open; none needs --render-url' }),
    'render-url': Flags.string({ helpValue: 'https-url', summary: 'With --tunnel none, the URL the dev version renders from' }),
    'take-over': Flags.boolean({ summary: "End another terminal's session on this app first" }),
    'write-env': pathFlag({ helpValue: 'file', summary: "Also write the session's variables to this gitignored file, mode 0600" }),
    'show-env': Flags.boolean({ summary: 'List the names of the variables the app receives' }),
    reset: Flags.boolean({ summary: 'Return your install to the serving version, and do nothing else', exclusive: RESET_CONFLICTS }),
    ...configFlag,
  }

  static override async exec(ctx: Ctx, { args, flags }: Parsed): Promise<void> {
    const [toml, cwd] = open(ctx, opt(flags.config))
    const row = resolve(toml, opt(args.name), cwd)
    if (flags.reset === true) return reset(ctx, row)
    const plan = await preflight(ctx, flags, toml, row, cwd)
    if (row.id !== undefined) plan.id = row.id
    else {
      const app = await register(ctx, toml, row, read(toml, row, cwd))
      // The registration printed the app; `apps dev` prints one value, at its exit.
      ctx.out.stdout = ''
      plan.id = str(app.id)
    }
    await session(ctx, plan)
  }
}
