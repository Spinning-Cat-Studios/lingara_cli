// The local verbs (ADR 16.10.26ae D4, D9): `apps status` reads the dev
// version; `apps pin`, `apps unpin` and `apps list --pinned` edit or read
// `platform.toml` alone (pin's one read is `app.get` for a name).
import { existsSync, realpathSync } from 'node:fs'
import { dirname, isAbsolute, join, relative, sep } from 'node:path'

import { DEFAULT_PORT, shown } from '../../platformToml.js'
import type { Ctx } from '../../lib/ctx.js'
import { get } from '../../lib/dev/devApi.js'
import { cwd as currentDir, loadAt, located, open, resolve } from '../../lib/dev/rows.js'
import { Exit, fromMessage, usage } from '../../lib/fail.js'
import { cell, render } from '../../lib/output.js'
import { APP, get as getObject, str } from '../../lib/platform/objects.js'
import { appNameOk } from '../../lib/project/schema.js'
import { FILE, pinRow, removeRow } from '../../lib/project/write.js'

/** D9's three lines. The lease time is `HH:MM` of the server's UTC stamp. */
export function statusLine(view: Record<string, unknown>): string {
  const url = view.render_url
  if (typeof url !== 'string') return 'dev: serving version'
  const session = (view.session ?? {}) as Record<string, unknown>
  if (session.state === 'live') {
    const lease = typeof session.lease_expires_at === 'string' ? session.lease_expires_at.slice(11, 16) : '?'
    return `dev: live ${url} (lease until ${lease} UTC)`
  }
  return `dev: parked (last ${url})`
}

/** `apps status`: live with its lease, parked with its last URL, or the serving version; `--json` is the `GET …/dev` body as the server answered. */
export async function status(ctx: Ctx, wanted: string | undefined, config: string | undefined): Promise<void> {
  const [toml, cwd] = open(ctx, config)
  const row = resolve(toml, wanted, cwd)
  if (row.id === undefined) throw usage(`${row.name} is not registered yet: run lingara apps update ${row.name}`)
  const view = await get(ctx, row.id)
  if (ctx.out.json) ctx.out.jsonValue(view)
  else ctx.out.print(`${statusLine(view)}\n`)
}

const uuidShaped = (s: string): boolean => /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(s)

/** A platform object's name as a local handle: `Luba Dive Log` → `luba-dive-log`. */
const handleOf = (name: string): string =>
  name
    .replace(/[^A-Za-z0-9]/g, '-')
    .toLowerCase()
    .split('-')
    .filter((w) => w !== '')
    .join('-')

const real = (p: string): string => (existsSync(p) ? realpathSync(p) : p)

function relativePath(project: string, base: string): string {
  const rel = relative(base, project)
  if (rel === '') return '.'
  return rel.startsWith('..') || isAbsolute(rel) ? project : rel.split(sep).join('/')
}

/** `apps pin <id|name> [--path <dir>]`: a row in platform.toml and, when it has none of that name, in the example. Never a platform write. */
export async function pin(ctx: Ctx, app: string, path: string | undefined, config: string | undefined): Promise<void> {
  const cwd = currentDir()
  let name = app
  let id: string | undefined
  if (uuidShaped(app)) {
    name = handleOf(str((await getObject(ctx, APP, app)).name))
    id = app
  } else if (path === undefined) throw usage(`pinning ${app} by name needs --path <dir>`)
  if (!appNameOk(name)) throw fromMessage(Exit.usage, 'app_name_invalid', { name })
  const found = located(ctx, config, cwd)
  const file = found !== undefined && existsSync(found) ? found : (found ?? join(cwd, FILE))
  const names = existsSync(file) ? loadAt(ctx, file, cwd).apps.map((r) => r.name) : []
  if (names.includes(name)) throw usage(`${shown(file, cwd)} already pins ${name}`)
  const project = real(join(cwd, path ?? '.'))
  pinRow(file, { name, path: relativePath(project, real(dirname(file))), port: DEFAULT_PORT }, id)
  ctx.out.nothing()
}

/** `apps unpin <name>`: platform.toml only; the shared example changes by review. */
export function unpin(ctx: Ctx, name: string, config: string | undefined): void {
  const [toml, cwd] = open(ctx, config)
  if (!removeRow(toml.file, name)) throw fromMessage(Exit.usage, 'app_row_unknown', { file: shown(toml.file, cwd), value: name })
  ctx.out.nothing()
}

/** `apps list --pinned`: the rows, as JSON or as a NAME/ID/PATH/PORT table. */
export function listPinned(ctx: Ctx, config: string | undefined): void {
  const [toml] = open(ctx, config)
  const rows = toml.apps.map((r) => ({ name: r.name, id: r.id ?? null, path: r.path, port: r.port }))
  if (ctx.out.json) return ctx.out.jsonValue(rows)
  ctx.out.print(render(['NAME', 'ID', 'PATH', 'PORT'], rows.map((r) => [r.name, cell(r.id), r.path, String(r.port)])))
}
