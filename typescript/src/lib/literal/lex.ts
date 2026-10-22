// The literal subset's tokens (spec/connection-literal.md; ADR 16.10.26ae
// D2): identifiers, JSON-escaped strings in either quote, finite JSON
// numbers and punctuation, each with its 1-based line and column, counted in
// code points. A comment, a template literal or a character the subset does
// not know stops the scan.
import { at, type Pos, Refusal } from './types.js'

export type Tok = { ident: string } | { str: string } | { num: number } | { punct: string }

export interface Token {
  tok: Tok
  at: Pos
}

class Lexer {
  i = 0
  constructor(
    public chars: string[],
    public line: number,
    public col = 1,
  ) {}

  pos(): Pos {
    return { line: this.line, column: this.col }
  }

  peek(ahead = 0): string | undefined {
    return this.chars[this.i + ahead]
  }

  bump(): string | undefined {
    const c = this.chars[this.i]
    if (c === undefined) return undefined
    this.i += 1
    if (c === '\n') {
      this.line += 1
      this.col = 1
    } else this.col += 1
    return c
  }

  takeWhile(keep: (c: string) => boolean): string {
    let out = ''
    for (let c = this.peek(); c !== undefined && keep(c); c = this.peek()) out += this.bump()
    return out
  }
}

const identStart = (c: string): boolean => /[A-Za-z_$]/.test(c)
const identChar = (c: string): boolean => /[A-Za-z0-9_$]/.test(c)
const PUNCT = '{}[](),:=.'

const SIMPLE: Record<string, string> = { '"': '"', '\\': '\\', '/': '/', b: '\b', f: '\f', n: '\n', r: '\r', t: '\t' }

/** JSON's escapes, and `\'` inside single quotes. */
function escape(lx: Lexer, quote: string): string | undefined {
  const c = lx.bump()
  if (c === undefined) return undefined
  if (c in SIMPLE) return SIMPLE[c]
  if (c === "'" && quote === "'") return "'"
  if (c !== 'u') return undefined
  const hex = [lx.bump(), lx.bump(), lx.bump(), lx.bump()].join('')
  return /^[0-9A-Fa-f]{4}$/.test(hex) ? String.fromCharCode(Number.parseInt(hex, 16)) : undefined
}

const RAW = new Set(['\n', '\r', '\t'])

/** One character of a string, unescaped; `null` at its closing quote. */
function stringChar(lx: Lexer, quote: string, start: Pos): string | null {
  const c = lx.bump()
  if (c === undefined || RAW.has(c)) throw at('text', start)
  if (c === quote) return null
  if (c === '$' && lx.peek() === '{') throw at('text', start)
  if (c !== '\\') return c
  const e = escape(lx, quote)
  if (e === undefined) throw at('text', start)
  return e
}

function string(lx: Lexer, start: Pos): string {
  const quote = lx.bump() as string
  let out = ''
  for (let c = stringChar(lx, quote, start); c !== null; c = stringChar(lx, quote, start)) out += c
  return out
}

/** A finite JSON number: `-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?`. */
function number(lx: Lexer, start: Pos): number {
  const text = lx.takeWhile((c) => /[A-Za-z0-9.+-]/.test(c))
  const ok = /^-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?$/.test(text)
  const n = Number(text)
  if (!ok || !Number.isFinite(n)) throw at('number', start)
  return n
}

/** A number, an identifier or punctuation. */
function scalar(lx: Lexer, c: string, start: Pos): Tok {
  if (c === '-' || /[0-9]/.test(c)) return { num: number(lx, start) }
  if (identStart(c)) return { ident: lx.takeWhile(identChar) }
  if (!PUNCT.includes(c)) throw at('syntax', start)
  lx.bump()
  return { punct: c }
}

function one(lx: Lexer, c: string, start: Pos): Tok {
  if (c === '/' && ['/', '*'].includes(lx.peek(1) ?? '')) throw at('comment', start)
  if (c === '`') throw at('text', start)
  if (c === '"' || c === "'") return { str: string(lx, start) }
  return scalar(lx, c, start)
}

/** Every token from `text`, which starts at line `firstLine`. */
export function tokens(text: string, firstLine: number): Token[] {
  const lx = new Lexer(Array.from(text), firstLine)
  const out: Token[] = []
  for (let c = lx.peek(); c !== undefined; c = lx.peek()) {
    if (c === ' ' || c === '\t' || c === '\r' || c === '\n') {
      lx.bump()
      continue
    }
    const start = lx.pos()
    out.push({ tok: one(lx, c, start), at: start })
  }
  return out
}

export { Refusal }
