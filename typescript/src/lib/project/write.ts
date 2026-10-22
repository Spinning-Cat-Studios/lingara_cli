// Writing `platform.toml`, its example and `.gitignore`, byte for byte as
// spec/templates.md says (ADR 16.10.26ae D1, D4), so every kit leaves the
// same files behind. The edits are line-based on purpose: a developer's
// comments and layout outside the touched row survive.
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

export const FILE = 'platform.toml'
export const EXAMPLE = 'platform.toml.example'
export const PLACEHOLDER_ID = '00000000-0000-0000-0000-000000000000'

export const FILE_HEADER =
  '# platform.toml — gitignored; copy from platform.toml.example.\n# Never put a secret here. lingara refuses to read a value that looks like one.\nschema = 1\n'
export const EXAMPLE_HEADER =
  '# platform.toml.example — checked in. Copy it to platform.toml (gitignored),\n# then run lingara apps update to register your own private copy of each app.\nschema = 1\n'

/** A row as `create` and `pin` write it. */
export interface NewRow {
  name: string
  path: string
  port: number
  start?: string
  /** `['manifest' | 'manifest_command', value]`. */
  manifest?: [string, string]
}

/** A TOML basic string. */
export const quote = (s: string): string => `"${s.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`

function rowText(row: NewRow, id: string | undefined): string {
  let out = `\n[[apps]]\nname = ${quote(row.name)}\n`
  if (id !== undefined) out += `id = ${quote(id)}\n`
  out += `path = ${quote(row.path)}\nport = ${row.port}\n`
  if (row.start !== undefined) out += `start = ${quote(row.start)}\n`
  if (row.manifest !== undefined) out += `${row.manifest[0]} = ${quote(row.manifest[1])}\n`
  return out
}

function readOr(path: string, header: string): string {
  let text: string
  try {
    text = readFileSync(path, 'utf8')
  } catch {
    return header
  }
  return text !== '' && !text.endsWith('\n') ? `${text}\n` : text
}

const lines = (text: string): string[] => (text.endsWith('\n') ? text.slice(0, -1) : text).split('\n')
const joined = (ls: string[]): string => ls.map((l) => `${l}\n`).join('')

/** Each `[[apps]]` row's line span `[start, end)`: up to the next table header that is not its own `[apps.env]`. */
function rows(text: string): [number, number][] {
  const ls = text === '' ? [] : lines(text)
  const header = (l: string): boolean => l.trimStart().startsWith('[') && l.trim() !== '[apps.env]'
  const starts = ls.flatMap((l, i) => (l.trim() === '[[apps]]' ? [i] : []))
  return starts.map((s) => {
    const next = ls.findIndex((l, i) => i > s && header(l))
    return [s, next === -1 ? ls.length : next]
  })
}

function spanOf(text: string, name: string): [number, number] | undefined {
  const want = `name = ${quote(name)}`
  const ls = text === '' ? [] : lines(text)
  return rows(text).find(([s, e]) => ls.slice(s, e).some((l) => l.trim() === want))
}

export const hasRow = (text: string, name: string): boolean => spanOf(text, name) !== undefined

/** Appends the row to `file` (with `id`, if any) and, when the example beside it has none of that name, to the example. */
export function pinRow(file: string, row: NewRow, id: string | undefined): void {
  writeFileSync(file, readOr(file, FILE_HEADER) + rowText(row, id))
  const example = join(dirname(file), EXAMPLE)
  const current = readOr(example, EXAMPLE_HEADER)
  if (!hasRow(current, row.name)) writeFileSync(example, current + rowText(row, PLACEHOLDER_ID))
}

/** Writes `id` into the row named `name`: replaces its `id` line, or adds one after its `name` line. */
export function setId(file: string, name: string, id: string): void {
  const text = readFileSync(file, 'utf8')
  const span = spanOf(text, name)
  if (span === undefined) return
  const ls = lines(text)
  const idLine = `id = ${quote(id)}`
  const isKey = (l: string, key: string): boolean => new RegExp(`^\\s*${key}\\s*=`).test(l)
  const existing = ls.findIndex((l, i) => i >= span[0] && i < span[1] && isKey(l, 'id'))
  if (existing !== -1) ls[existing] = idLine
  else {
    const nameAt = ls.findIndex((l, i) => i >= span[0] && i < span[1] && isKey(l, 'name'))
    ls.splice((nameAt === -1 ? span[0] : nameAt) + 1, 0, idLine)
  }
  writeFileSync(file, joined(ls))
}

/** Removes the row named `name`; false when there is none. */
export function removeRow(file: string, name: string): boolean {
  const text = readFileSync(file, 'utf8')
  const span = spanOf(text, name)
  if (span === undefined) return false
  const ls = lines(text)
  let [start] = span
  if (span[1] === ls.length) while (start > 0 && ls[start - 1].trim() === '') start -= 1
  writeFileSync(file, joined([...ls.slice(0, start), ...ls.slice(span[1])]))
  return true
}

/** Adds a `platform.toml` line to `<dir>/.gitignore` when no line is one. */
export function ensureGitignore(dir: string): void {
  const path = join(dir, '.gitignore')
  const text = readOr(path, '')
  if (text.split('\n').some((l) => l.trim() === FILE)) return
  writeFileSync(path, `${text}${FILE}\n`)
}
