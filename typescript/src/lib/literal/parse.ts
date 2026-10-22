// Statements and values over the tokens (spec/connection-literal.md):
// `const <handle> = lingara.<kind>(<value>)`, one after another. A second
// call argument is a script, which refuses the file there and then.
import type { Token } from './lex.js'
import { at, type Lit, type Member, type Pos, scripted, type Statement } from './types.js'

const KINDS = ['client', 'secret', 'webhook', 'app']

class Parser {
  i = 0
  handles = new Set<string>()
  constructor(
    public toks: Token[],
    public end: Pos,
  ) {}

  peek(): Token | undefined {
    return this.toks[this.i]
  }

  isPunct(c: string): boolean {
    const t = this.peek()
    return t !== undefined && 'punct' in t.tok && t.tok.punct === c
  }

  next(): Token {
    const t = this.peek()
    if (t === undefined) throw at('syntax', this.end)
    this.i += 1
    return t
  }

  punct(c: string): Pos {
    const t = this.next()
    if (!('punct' in t.tok) || t.tok.punct !== c) throw at('syntax', t.at)
    return t.at
  }

  ident(): [string, Pos] {
    const t = this.next()
    if (!('ident' in t.tok)) throw at('syntax', t.at)
    return [t.tok.ident, t.at]
  }

  word(want: string): Pos {
    const [w, pos] = this.ident()
    if (w !== want) throw at('syntax', pos)
    return pos
  }

  statement(): Statement {
    const pos = this.word('const')
    const [handle, handleAt] = this.ident()
    if (this.handles.has(handle)) throw at('handle', handleAt)
    this.punct('=')
    this.word('lingara')
    this.punct('.')
    const [kind, kindAt] = this.ident()
    if (!KINDS.includes(kind)) throw at('syntax', kindAt)
    this.punct('(')
    const value = this.value()
    if (this.isPunct(',')) throw scripted(handle)
    this.punct(')')
    // Declared only now: a value may name the statements above it, never its own.
    this.handles.add(handle)
    return { handle, kind, value, at: pos }
  }

  value(): Lit {
    const t = this.next()
    const tok = t.tok
    if ('str' in tok) return { str: tok.str }
    if ('num' in tok) return { num: tok.num }
    if ('ident' in tok) return this.wordValue(tok.ident, t.at)
    if (tok.punct === '[') return { arr: this.items(']', () => this.value()) }
    if (tok.punct === '{') return { obj: this.object(), at: t.at }
    throw at('syntax', t.at)
  }

  wordValue(w: string, pos: Pos): Lit {
    if (w === 'true' || w === 'false') return { bool: w === 'true' }
    if (w === 'null') return { nil: true }
    if (this.handles.has(w)) return { ref: w }
    throw at('reference', pos)
  }

  /** `item {, item} [,] close`, the opener already read. */
  items<T>(close: string, item: () => T): T[] {
    const out: T[] = []
    for (;;) {
      if (this.isPunct(close)) {
        this.i += 1
        return out
      }
      out.push(item())
      const t = this.next()
      if ('punct' in t.tok && t.tok.punct === close) return out
      if (!('punct' in t.tok) || t.tok.punct !== ',') throw at('syntax', t.at)
    }
  }

  object(): Member[] {
    const seen = new Set<string>()
    return this.items('}', () => {
      const t = this.next()
      const key = 'ident' in t.tok ? t.tok.ident : 'str' in t.tok ? t.tok.str : undefined
      if (key === undefined) throw at('syntax', t.at)
      if (seen.has(key)) throw at('key', t.at)
      seen.add(key)
      this.punct(':')
      return { key, at: t.at, value: this.value() }
    })
  }
}

/** Every statement of the body, which ends at `end`. */
export function statements(toks: Token[], end: Pos): Statement[] {
  const p = new Parser(toks, end)
  const out: Statement[] = []
  while (p.peek() !== undefined) out.push(p.statement())
  return out
}
