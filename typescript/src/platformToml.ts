// `platform.toml` (ADR 16.10.26ae D4): the gitignored, per-developer file
// naming the apps a checkout works on. Found by `--config`, then
// `LINGARA_PLATFORM_TOML`, then by walking up from the current directory as
// Cargo does. Read in a fixed order, so the first refusal is the same in
// every kit: TOML syntax (`platform_toml_schema`), a secret-shaped value or
// key (`platform_toml_secret`), then the rules
// `spec/platform-toml.schema.json` writes (`platform_toml_schema`). A tracked
// file is only a warning (`lib/project/git.ts`).
import { existsSync, readFileSync, statSync } from 'node:fs'
import { dirname, isAbsolute, join, relative } from 'node:path'

import { parse as parseToml } from 'smol-toml'

import type { Env } from './lib/env.js'
import { Exit, Fail, fromMessage } from './lib/fail.js'
import { check } from './lib/project/schema.js'
import { firstSecret } from './lib/project/secrets.js'
import { FILE, PLACEHOLDER_ID } from './lib/project/write.js'

export const DEFAULT_PORT = 8787

/** One `[[apps]]` row. */
export interface Row {
  name: string
  id?: string
  path: string
  port: number
  start?: string
  render_path?: string
  manifest?: string
  manifest_command?: string
  credentials: string[]
  env_file?: string
  env: Record<string, string>
}

export interface PlatformToml {
  file: string
  api?: string
  profile?: string
  apps: Row[]
}

/** The directory the file is in, which every row's `path` is relative to. */
export const tomlDir = (toml: PlatformToml): string => dirname(toml.file)
export const projectDir = (toml: PlatformToml, row: Row): string => join(tomlDir(toml), row.path)
/** The file's name as messages print it: relative to `cwd` where it can be. */
export const shown = (path: string, cwd: string): string => (path.startsWith(`${cwd}/`) ? relative(cwd, path) : path)

const isFile = (p: string): boolean => existsSync(p) && statSync(p).isFile()

/** The nearest `platform.toml` in `from` or a directory above it. */
export function findUpward(from: string): string | undefined {
  for (let dir = from; ; dir = dirname(dir)) {
    if (isFile(join(dir, FILE))) return join(dir, FILE)
    if (dirname(dir) === dir) return undefined
  }
}

/** `--config`, then `LINGARA_PLATFORM_TOML`, then the nearest `platform.toml` at or above `cwd`. */
export function locate(config: string | undefined, env: Env, cwd: string): string | undefined {
  const explicit = config ?? env.get('LINGARA_PLATFORM_TOML')
  if (explicit === undefined) return findUpward(cwd)
  return isAbsolute(explicit) ? explicit : join(cwd, explicit)
}

export const schemaFail = (file: string, reason: string): Fail => fromMessage(Exit.platformTomlSchema, 'platform_toml_schema', { file, reason })

const text = (v: unknown): string | undefined => (typeof v === 'string' ? v : undefined)

function row(v: Record<string, unknown>): Row {
  const id = text(v.id)
  const env = typeof v.env === 'object' && v.env !== null ? (v.env as Record<string, string>) : {}
  return {
    name: text(v.name) ?? '',
    id: id === PLACEHOLDER_ID ? undefined : id,
    path: text(v.path) ?? '.',
    port: typeof v.port === 'number' ? v.port : DEFAULT_PORT,
    start: text(v.start),
    render_path: text(v.render_path),
    manifest: text(v.manifest),
    manifest_command: text(v.manifest_command),
    credentials: Array.isArray(v.credentials) ? v.credentials.filter((c): c is string => typeof c === 'string') : [],
    env_file: text(v.env_file),
    env,
  }
}

/** Reads and checks `path`; `name` is how messages name it. */
export function load(path: string, name: string): PlatformToml {
  let value: Record<string, unknown>
  try {
    value = parseToml(readFileSync(path, 'utf8')) as Record<string, unknown>
  } catch (e) {
    throw schemaFail(name, (e as Error).message.split('\n')[0])
  }
  const key = firstSecret(value)
  if (key !== undefined) throw fromMessage(Exit.platformTomlSecret, 'platform_toml_secret', { file: name, key })
  const reason = check(value)
  if (reason !== undefined) throw schemaFail(name, reason)
  const platform = (value.platform ?? {}) as Record<string, unknown>
  const apps = Array.isArray(value.apps) ? (value.apps as Record<string, unknown>[]).map(row) : []
  return { file: path, api: text(platform.api), profile: text(platform.profile), apps }
}
