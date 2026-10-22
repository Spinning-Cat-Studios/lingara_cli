// Rendering a template (ADR 16.10.26ae D2, D3; spec/templates.md): the fixed
// `{{key}}` set, the array syntax each language's bare template uses, the two
// renaming rules, and `template.toml`, read and never written.
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

import { parse as parseToml } from 'smol-toml'

import { template } from '../assets.js'
import { failure } from '../fail.js'

/** What the scaffold substitutes, each derived once (spec/templates.md). */
export interface Keys {
  name: string
  displayName: string
  description: string
  port: number
  slots: string[]
  context: string[]
}

/** `template.toml`: the row's start command and its manifest source. */
export interface Meta {
  start?: string
  manifest?: [string, string]
}

export interface Rendered {
  path: string
  bytes: string
}

const META = 'template.toml'

/** `["a", "b"]` in the syntax the template's language writes an array in. */
export function array(lang: string, variant: string, items: string[]): string {
  const inner = items.map((i) => `"${i}"`).join(', ')
  if (variant === 'bare' && lang === 'go') return `[]string{${inner}}`
  if (variant === 'bare' && lang === 'java') return `List.of(${inner})`
  if (variant === 'bare' && lang === 'kotlin') return `listOf(${inner})`
  return `[${inner}]`
}

/** A segment starting `_` is written with `.`; a `.tmpl` suffix is dropped. */
export function projectPath(relativePath: string): string {
  const segments = relativePath.split('/').map((s) => (s.startsWith('_') ? `.${s.slice(1)}` : s))
  const last = segments.length - 1
  if (segments[last].endsWith('.tmpl')) segments[last] = segments[last].slice(0, -'.tmpl'.length)
  return segments.join('/')
}

export function substitute(text: string, lang: string, variant: string, keys: Keys): string {
  const pairs: [string, string][] = [
    ['{{name}}', keys.name],
    ['{{display_name}}', keys.displayName],
    ['{{description}}', keys.description],
    ['{{port}}', String(keys.port)],
    ['{{slots}}', array(lang, variant, keys.slots)],
    ['{{context}}', array(lang, variant, keys.context)],
  ]
  return pairs.reduce((t, [k, v]) => t.split(k).join(v), text)
}

function parseMeta(text: string): Meta {
  const meta = parseToml(text) as Record<string, unknown>
  const get = (k: string): string | undefined => (typeof meta[k] === 'string' ? (meta[k] as string) : undefined)
  const manifest = get('manifest')
  const command = get('manifest_command')
  const source: [string, string] | undefined = manifest !== undefined ? ['manifest', manifest] : command !== undefined ? ['manifest_command', command] : undefined
  return { start: get('start'), manifest: source }
}

/** Every file of `<lang>/<variant>`, rendered, and its `template.toml`. */
export function render(lang: string, variant: string, keys: Keys): [Rendered[], Meta] {
  const files = template(lang, variant)
  if (files.length === 0) throw failure(`this lingara carries no ${lang} ${variant} template`)
  let meta: Meta = {}
  const out: Rendered[] = []
  for (const [path, bytes] of files) {
    if (path === META) meta = parseMeta(bytes.toString('utf8'))
    else out.push({ path: projectPath(path), bytes: substitute(bytes.toString('utf8'), lang, variant, keys) })
  }
  return [out, meta]
}

/** Writes every file under `dir`, creating directories as it goes. */
export function writeAll(dir: string, files: Rendered[]): void {
  for (const file of files) {
    const path = join(dir, file.path)
    mkdirSync(dirname(path), { recursive: true })
    writeFileSync(path, file.bytes)
  }
}
