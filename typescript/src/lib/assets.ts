// Every shared file this kit reads, from its own `assets/` (ADR 16.10.26ae
// D12): `endpoints.json` (where production is), `messages.en.json` (what a
// prompt says, byte for byte), `tools.toml` (cloudflared's floor) and the app
// templates. All are committed copies, held equal to their sources by `make
// check-kit-assets`, because a published package carries only its own
// directory. Read relative to this module, so `src/` and `dist/` agree.
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { parse as parseToml } from 'smol-toml'

const ASSETS = fileURLToPath(new URL('../../assets/', import.meta.url))
const read = (name: string): string => readFileSync(join(ASSETS, name), 'utf8')

interface MessageFile {
  messages: { id: string; text: string }[]
}

const TEXTS = new Map((JSON.parse(read('messages.en.json')) as MessageFile).messages.map((m) => [m.id, m.text]))

/** The message `id` with each `{name}` filled. */
export function message(id: string, vars: Record<string, string> = {}): string {
  let text = TEXTS.get(id) ?? id
  for (const [k, v] of Object.entries(vars)) text = text.split(`{${k}}`).join(v)
  return text
}

export function hasMessage(id: string): boolean {
  return TEXTS.has(id)
}

export interface Endpoints {
  /** The first-party public client `lingara login` signs in as (16.10.26u §3). */
  client_id: string
  api_url: string
  platform_url: string
}

export const ENDPOINTS = JSON.parse(read('endpoints.json')) as Endpoints

/** `package.json`'s version, which is the repository's VERSION. */
export const VERSION = (JSON.parse(readFileSync(new URL('../../package.json', import.meta.url), 'utf8')) as { version: string }).version

/** `tools.toml`'s `[cloudflared] min_version`, as dotted numbers. */
export function cloudflaredMinVersion(): number[] {
  const tools = parseToml(read('tools.toml')) as { cloudflared?: { min_version?: string } }
  return (tools.cloudflared?.min_version ?? '').split('.').map(Number).filter((n) => !Number.isNaN(n))
}

function walk(dir: string, prefix: string, out: [string, Buffer][]): void {
  for (const name of readdirSync(dir).sort()) {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) walk(path, `${prefix}${name}/`, out)
    else out.push([`${prefix}${name}`, readFileSync(path)])
  }
}

/** Every file of one template (`<lang>/<variant>/`), path relative to it; empty when there is none. */
export function template(lang: string, variant: string): [string, Buffer][] {
  const out: [string, Buffer][] = []
  try {
    walk(join(ASSETS, 'templates', 'apps', lang, variant), '', out)
  } catch {
    return []
  }
  return out
}
