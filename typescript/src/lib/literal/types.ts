// The literal subset's values and refusals (spec/connection-literal.md).

export interface Pos {
  line: number
  column: number
}

/** Why the file is refused: a code at a position, or a scripted statement. */
export class Refusal extends Error {
  constructor(
    public code: string,
    public pos?: Pos,
    public statement?: string,
  ) {
    super(code)
  }
}

export const at = (code: string, pos: Pos): Refusal => new Refusal(code, pos)
export const scripted = (handle: string): Refusal => new Refusal('connection_scripted', undefined, handle)

export interface Member {
  key: string
  at: Pos
  value: Lit
}

export type Lit =
  | { str: string }
  | { num: number }
  | { bool: boolean }
  | { nil: true }
  | { arr: Lit[] }
  | { obj: Member[]; at: Pos }
  | { ref: string }

export interface Statement {
  handle: string
  kind: string
  value: Lit
  /** Where `const` is. */
  at: Pos
}

/** As JSON; a reference becomes its handle. */
export function toJson(lit: Lit): unknown {
  if ('str' in lit) return lit.str
  if ('ref' in lit) return lit.ref
  if ('num' in lit) return lit.num
  if ('bool' in lit) return lit.bool
  if ('nil' in lit) return null
  if ('arr' in lit) return lit.arr.map(toJson)
  return Object.fromEntries(lit.obj.map((m) => [m.key, toJson(m.value)]))
}
