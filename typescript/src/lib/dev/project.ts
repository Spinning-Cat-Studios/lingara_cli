// A project's manifest and its registration (ADR 16.10.26ae D1 step 2, D6
// steps 1, 4 and 5): read from `lingara/connection.js` by the literal reader,
// or from `manifest_command`'s stdout; checked; its warnings printed; and,
// for a row with no id, registered as a private app.
import { spawnSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { join, relative } from 'node:path'

import { appManifest, Refusal, refusalFail } from '../../connectionLiteral.js'
import { type PlatformToml, projectDir, type Row } from '../../platformToml.js'
import { shellArgs } from '../../supervise.js'
import { message } from '../assets.js'
import type { Ctx } from '../ctx.js'
import { Exit, Fail, failure, usage } from '../fail.js'
import { Creation } from '../platform/creation.js'
import { APP, OAUTH_CLIENT, type Obj, str } from '../platform/objects.js'
import { setId } from '../project/write.js'
import { brokenRule, warnings, warningText } from './manifest.js'

/** The manifest's source as messages name it, relative to `cwd`. */
export function source(toml: PlatformToml, row: Row, cwd: string): string {
  if (row.manifest_command !== undefined) return row.manifest_command
  const file = join(projectDir(toml, row), row.manifest ?? 'lingara/connection.js')
  return file.startsWith(`${cwd}/`) ? relative(cwd, file) : file
}

/** D3: `manifest_command` through the platform shell, in the project; its stdout is the wire manifest. */
function runCommand(command: string, dir: string): Obj {
  const [shell, args] = shellArgs(command)
  const out = spawnSync(shell, args, { cwd: dir, encoding: 'utf8', stdio: ['ignore', 'pipe', 'inherit'] })
  if (out.status !== 0) throw failure(`${command} exited ${out.status ?? 'on a signal'}; fix it, then run lingara apps update`)
  try {
    return JSON.parse(out.stdout) as Obj
  } catch (e) {
    throw usage(`${command} printed no manifest JSON: ${(e as Error).message}`)
  }
}

function fromConnection(toml: PlatformToml, row: Row, name: string): Obj {
  const file = join(projectDir(toml, row), row.manifest ?? 'lingara/connection.js')
  let text: string
  try {
    text = readFileSync(file, 'utf8')
  } catch (e) {
    throw usage(`${name}: ${(e as Error).message}`)
  }
  try {
    return appManifest(text)
  } catch (e) {
    throw e instanceof Refusal ? refusalFail(e, name) : e
  }
}

/** The wire manifest the project describes, checked before any request. */
export function read(toml: PlatformToml, row: Row, cwd: string): Obj {
  const name = source(toml, row, cwd)
  const manifest = row.manifest_command !== undefined ? runCommand(row.manifest_command, projectDir(toml, row)) : fromConnection(toml, row, name)
  const rule = brokenRule(manifest)
  if (rule !== undefined) throw new Fail(Exit.usage, 'manifest_invalid', `${name}: the manifest breaks the ${rule} rule`)
  return manifest
}

/** D6 step 5: one `manifest_warning` line each, never a failure. */
export function warn(ctx: Ctx, manifest: Obj): string[] {
  const codes = warnings(manifest)
  for (const code of codes) ctx.out.note(message('manifest_warning', { code, text: warningText(code) }))
  return codes
}

/** The display name D2 derives: each `-` a space, each word capitalised. */
export const displayName = (name: string): string =>
  name
    .split('-')
    .map((w) => (w === '' ? '' : w[0].toUpperCase() + w.slice(1)))
    .join(' ')

/**
 * D1 step 2 / D6 step 4: the private OAuth client, then the app under it
 * through the registration form's own steps, the id written into the row.
 * Prints the app as the registration form does.
 */
export async function register(ctx: Ctx, toml: PlatformToml, row: Row, manifest: Obj): Promise<Obj> {
  const title = displayName(row.name)
  const clientBody = { client_type: 'confidential', allowed_scopes: [], billing_mode: 'allowance', redirect_uris: [] }
  const made = new Creation()
  const app = await made.run(ctx, async () => {
    const client = await made.object(ctx, OAUTH_CLIENT, title, undefined, clientBody)
    const created = await made.object(ctx, APP, title, str(client.id), { manifest })
    return [APP, str(created.id)]
  })
  setId(toml.file, row.name, str(app.id))
  return app
}
