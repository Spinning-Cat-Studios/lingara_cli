import { describe, expect, it } from 'vitest'

import { cell, fields, render } from '../src/lib/output.js'

describe("16.10.26ad AC16 the table is B1's", () => {
  it('pads to the widest cell, two spaces apart, no trailing whitespace, no truncation, - for empty', () => {
    const text = render(['ID', 'NAME', 'SERVING'], [['7d1e', 'Flashcards', '1.0.0'], ['7d1e2f30-long', 'Tone drills', '']])
    expect(text).toBe('ID             NAME         SERVING\n7d1e           Flashcards   1.0.0\n7d1e2f30-long  Tone drills  -\n')
    expect(text.split('\n').every((l) => !l.endsWith(' '))).toBe(true)
    const long = 'x'.repeat(200)
    expect(render(['A'], [[long]])).toContain(long)
    expect(render(['SCOPES', 'B'], [['a', 'b']])).toBe('SCOPES  B\na       b\n')
  })

  it('renders JSON values as cells', () => {
    expect([cell(null), cell([]), cell(['a:read', 'b:write']), cell(true), cell(3), cell({ a: 1 })]).toEqual(['-', '-', 'a:read,b:write', 'true', '3', '{"a":1}'])
    expect(fields({ id: 'x', name: null })).toEqual([['id', 'x'], ['name', '-']])
  })
})
