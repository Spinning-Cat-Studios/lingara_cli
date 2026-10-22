// Contract B1's streams and table, and B2's refusal line (ADR 16.10.26ad D6).
// Data goes to stdout, buffered and written once by `main`, so an in-process
// verb that canvas apply runs hands its JSON back instead; everything else
// goes to stderr. The table is rendered here rather than by `ux.table`,
// which draws borders and wraps cells, both of which B1 forbids.
import type { Fail } from './fail.js'

export interface Sink {
  write(text: string): void
}

/** An empty value is `-`, so no cell is blank and the split stays exact. */
const cellText = (c: string | undefined): string => (c === undefined || c === '' ? '-' : c)

/** A header and its rows: left-aligned, two spaces apart, no trailing whitespace, never truncated. */
export function render(columns: string[], rows: string[][]): string {
  const all = [columns, ...rows]
  const widths = columns.map((_, i) => Math.max(...all.map((r) => [...cellText(r[i])].length)))
  return all.map((r) => `${columns.map((_, i) => cellText(r[i]).padEnd(widths[i])).join('  ').trimEnd()}\n`).join('')
}

/** One JSON value as a cell: scalars as text, lists of scalars joined with commas, `null` and `[]` as `-`. */
export function cell(value: unknown): string {
  if (value === null || value === undefined) return '-'
  if (typeof value === 'string') return value
  if (Array.isArray(value)) {
    if (value.length === 0) return '-'
    if (value.every((v) => typeof v !== 'object' || v === null)) return value.map(cell).join(',')
  }
  return JSON.stringify(value)
}

/** A `get`: B1's `FIELD  VALUE` form, one row per member in the server's order. */
export function fields(object: unknown): string[][] {
  if (typeof object !== 'object' || object === null || Array.isArray(object)) return [['value', cell(object)]]
  return Object.entries(object).map(([k, v]) => [k, cell(v)])
}

export class Output {
  stdout = ''

  constructor(
    private err: Sink,
    public json: boolean,
  ) {}

  print(text: string): void {
    this.stdout += text
  }

  /** One line on stderr. */
  note(line: string): void {
    this.err.write(`${line}\n`)
  }

  /** A prompt on stderr, left on the line it ends. */
  prompt(text: string): void {
    this.err.write(text)
  }

  jsonValue(value: unknown): void {
    this.print(`${JSON.stringify(value)}\n`)
  }

  /** One server value: as JSON, or as the `FIELD  VALUE` table. */
  value(value: unknown): void {
    if (this.json) this.jsonValue(value)
    else this.print(render(['FIELD', 'VALUE'], fields(value)))
  }

  /** A list: one JSON array, or the schema's columns read from each item. */
  list(items: unknown[], columns: [string, string][]): void {
    if (this.json) {
      this.jsonValue(items)
      return
    }
    const rows = items.map((item) => columns.map(([, key]) => cell((item as Record<string, unknown>)[key])))
    this.print(render(columns.map(([h]) => h), rows))
  }

  /** A command with nothing to print: nothing, or `{}` under `--json`. */
  nothing(): void {
    if (this.json) this.print('{}\n')
  }
}

/** The lines `fail` prints on stderr: B2's one line and its advice, or one JSON line. */
export function errorLines(fail: Fail, json: boolean): string[] {
  if (json) {
    const error: Record<string, unknown> = { code: fail.code, message: fail.message, exit: fail.exit }
    if (fail.hints.length > 0) error.hints = fail.hints
    if (fail.made.length > 0) error.made = fail.made
    return [JSON.stringify({ error })]
  }
  return [`error: ${fail.message} (${fail.code})`, ...fail.hints, ...fail.made.map((m) => `made: ${m.kind} ${m.id}`)]
}
