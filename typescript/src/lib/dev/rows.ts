// Opening `platform.toml` for a command and picking its row (ADR 16.10.26ae
// D4, D7 step 1). Opening runs every refusal before any request, warns once
// when git tracks the file, and applies its `[platform]` table: `api` unless
// `LINGARA_API_URL` is set, `profile` unless `--profile` or `LINGARA_PROFILE`
// chose one.
import { realpathSync } from 'node:fs'
import { relative, sep } from 'node:path'

import { locate, load, type PlatformToml, projectDir, type Row, schemaFail, shown } from '../../platformToml.js'
import { message } from '../assets.js'
import type { Ctx } from '../ctx.js'
import { Exit, fromMessage, usage } from '../fail.js'
import { tracked } from '../project/git.js'

const real = (p: string): string => {
  try {
    return realpathSync(p)
  } catch {
    return p
  }
}

/** The process's directory, through any symlink, so a row's path compares. */
export const cwd = (): string => real(process.cwd())

/** The file `--config`, `LINGARA_PLATFORM_TOML` or the walk up names, if any. */
export const located = (ctx: Ctx, config: string | undefined, dir: string): string | undefined => locate(config, ctx.app.env, dir)

/** Reads and checks the file at `path`, warns if it is tracked, and applies its `[platform]` table. */
export function loadAt(ctx: Ctx, path: string, dir: string): PlatformToml {
  const name = shown(path, dir)
  const toml = load(path, name)
  if (tracked(path)) ctx.out.note(message('platform_toml_tracked', { path: name }))
  if (ctx.app.env.get('LINGARA_API_URL') === undefined && toml.api !== undefined) ctx.app.http.bases.api = toml.api
  if (!ctx.profileChosen && toml.profile !== undefined) ctx.profile = toml.profile
  return toml
}

/** The command's `platform.toml`, which must exist. */
export function open(ctx: Ctx, config: string | undefined): [PlatformToml, string] {
  const dir = cwd()
  const path = located(ctx, config, dir)
  if (path === undefined) throw usage(`no platform.toml at or above ${dir}: run lingara apps create, or lingara apps pin`)
  try {
    return [loadAt(ctx, path, dir), dir]
  } catch (e) {
    if ((e as NodeJS.ErrnoException).code === 'ENOENT') throw schemaFail(path, 'the file does not exist')
    throw e
  }
}

const unknown = (toml: PlatformToml, dir: string, value: string) =>
  fromMessage(Exit.usage, 'app_row_unknown', { file: shown(toml.file, dir), value })

/** How deep the row's project is, when it contains `dir`. */
function depth(toml: PlatformToml, row: Row, dir: string): number | undefined {
  const project = real(projectDir(toml, row))
  const rel = relative(project, dir)
  return rel === '' || (!rel.startsWith('..') && !rel.startsWith(sep)) ? project.split(sep).length : undefined
}

/** The row named (by `name` or `id`), or with none named: the only row, or the one whose project contains `dir` (the deepest, when they nest). */
export function resolve(toml: PlatformToml, wanted: string | undefined, dir: string): Row {
  if (wanted !== undefined) {
    const found = toml.apps.find((r) => r.name === wanted || r.id === wanted)
    if (found === undefined) throw unknown(toml, dir, wanted)
    return found
  }
  if (toml.apps.length === 1) return toml.apps[0]
  const containing = toml.apps.flatMap((r) => {
    const d = depth(toml, r, dir)
    return d === undefined ? [] : [[d, r] as [number, Row]]
  })
  containing.sort((a, b) => b[0] - a[0])
  if (containing.length > 0 && (containing.length === 1 || containing[1][0] < containing[0][0])) return containing[0][1]
  if (toml.apps.length === 0) throw unknown(toml, dir, '(no app is pinned)')
  throw fromMessage(Exit.usage, 'app_row_ambiguous', { file: shown(toml.file, dir), names: toml.apps.map((r) => r.name).join(', ') })
}
