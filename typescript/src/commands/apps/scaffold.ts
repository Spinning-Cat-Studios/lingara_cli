// `lingara apps create <name>` without `--manifest` (ADR 16.10.26ae D1):
// scaffold offline, pin the project in `platform.toml`, then register it as a
// private app unless `--no-register` or there is no session.
import { existsSync, mkdirSync, readdirSync, realpathSync, statSync } from 'node:fs'
import { basename, dirname, isAbsolute, join, relative, sep } from 'node:path'

import { DEFAULT_PORT, findUpward } from '../../platformToml.js'
import { message } from '../../lib/assets.js'
import type { Ctx } from '../../lib/ctx.js'
import { displayName, read, register, warn } from '../../lib/dev/project.js'
import { cwd as currentDir, loadAt, located, resolve } from '../../lib/dev/rows.js'
import { type Keys, render, writeAll } from '../../lib/dev/template.js'
import { Exit, fromMessage, usage } from '../../lib/fail.js'
import { appNameOk } from '../../lib/project/schema.js'
import { ensureGitignore, FILE, pinRow } from '../../lib/project/write.js'

export interface ScaffoldArgs {
  name: string
  lang: string
  bare: boolean
  dir?: string
  noRegister: boolean
  description?: string
  port?: number
  slots: string[]
  context: string[]
  config?: string
}

const DEFAULT_DESCRIPTION = 'A Lingara app.'

/** The description goes inside a string literal in every template language. */
const descriptionOk = (s: string): boolean => !/["\\`$#{}]/.test(s) && !/\p{Cc}/u.test(s)

function keys(a: ScaffoldArgs): Keys {
  if (!appNameOk(a.name)) throw fromMessage(Exit.usage, 'app_name_invalid', { name: a.name })
  const description = a.description ?? DEFAULT_DESCRIPTION
  if (!descriptionOk(description)) throw usage('--description may not hold ", \\, `, $, #, { or }, or a control character')
  const bad = a.context.find((c) => !/^[a-z][a-z_]*$/.test(c))
  if (bad !== undefined) throw usage(`--context ${bad} is not a context slice name`)
  const slots = a.slots.length === 0 ? ['home.side'] : a.slots
  return { name: a.name, displayName: displayName(a.name), description, port: a.port ?? DEFAULT_PORT, slots, context: a.context }
}

const real = (p: string): string => (existsSync(p) ? realpathSync(p) : p)
const shownPath = (path: string, dir: string): string => (path.startsWith(`${dir}${sep}`) ? relative(dir, path) : path)

/** `dir` relative to `base`, `/`-separated; `.` when they are one. */
function relativeTo(dir: string, base: string): string {
  const rel = relative(base, dir)
  if (rel === '') return '.'
  return rel.startsWith('..') || isAbsolute(rel) ? dir : rel.split(sep).join('/')
}

/** Step 3's file: `--config` or `LINGARA_PLATFORM_TOML`, else the nearest above the project's parent, else a new one inside the project. */
function pinFile(ctx: Ctx, a: ScaffoldArgs, dir: string, cwd: string): string {
  const explicit = a.config !== undefined || ctx.app.env.get('LINGARA_PLATFORM_TOML') !== undefined ? located(ctx, a.config, cwd) : undefined
  const found = explicit ?? findUpward(dirname(dir))
  if (found === undefined || !existsSync(found) || !statSync(found).isFile()) return join(dir, FILE)
  if (loadAt(ctx, found, cwd).apps.some((r) => r.name === a.name)) throw usage(`${shownPath(found, cwd)} already pins ${a.name}`)
  return found
}

/** Step 2, last: offline when `--no-register`, or with no session to use. */
async function registerIt(ctx: Ctx, a: ScaffoldArgs, file: string, cwd: string): Promise<void> {
  if (a.noRegister) return ctx.out.nothing()
  if (ctx.ciPair() === undefined && (await ctx.app.stores.load(ctx.profile)) === undefined) {
    ctx.out.note(message('apps_create_login_hint', { name: a.name }))
    return ctx.out.nothing()
  }
  const toml = loadAt(ctx, file, cwd)
  const row = resolve(toml, a.name, cwd)
  const manifest = read(toml, row, cwd)
  warn(ctx, manifest)
  await register(ctx, toml, row, manifest)
}

export async function scaffold(ctx: Ctx, a: ScaffoldArgs): Promise<void> {
  const k = keys(a)
  const cwd = currentDir()
  const wanted = a.dir === undefined ? join(cwd, a.name) : isAbsolute(a.dir) ? a.dir : join(cwd, a.dir)
  if (existsSync(wanted) && statSync(wanted).isDirectory() && readdirSync(wanted).length > 0) {
    throw fromMessage(Exit.usage, 'dir_not_empty', { path: shownPath(wanted, cwd) })
  }
  const [files, meta] = render(a.lang, a.bare ? 'bare' : 'default', k)
  const file = pinFile(ctx, a, wanted, cwd)
  mkdirSync(wanted, { recursive: true })
  const dir = real(wanted)
  writeAll(dir, files)
  const base = real(dirname(file))
  const target = join(base, basename(file))
  pinRow(target, { name: a.name, path: relativeTo(dir, base), port: k.port, start: meta.start, manifest: meta.manifest }, undefined)
  ensureGitignore(base)
  await registerIt(ctx, a, target, cwd)
}
