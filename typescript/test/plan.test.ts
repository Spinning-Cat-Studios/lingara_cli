import { readFileSync } from 'node:fs'

import { describe, expect, it } from 'vitest'

import { type Part, type Recording, shellLine, type Step, steps, Stop, type Verdict } from '../src/lib/canvas/argv.js'
import { readDefinition } from '../src/lib/canvas/definition.js'
import { buildPlan, type Live, type Records, Refusal, rowOf, stillLive } from '../src/lib/canvas/plan.js'

interface Vector {
  name: string
  definition: unknown
  recorded: Record<string, { id: string }>
  live: Record<string, unknown[]>
  expect: { kept?: Record<string, string>; ops?: unknown[]; refused?: string }
  verdicts?: Verdict[]
  steps?: unknown[]
  stop?: unknown
}

const VECTORS = (JSON.parse(readFileSync(new URL('../../conformance/vectors/canvas-plans.json', import.meta.url), 'utf8')) as { vectors: Vector[] }).vectors

const live = (v: Record<string, unknown[]>): Live => ({ clients: (v.oauth_client ?? []).map(rowOf), webhooks: (v.webhook_out ?? []).map(rowOf), apps: (v.app ?? []).map(rowOf) })
const records = (v: Record<string, { id: string }>): Records => new Map(Object.entries(v).map(([h, r]) => [h, r.id]))
const word = (p: Part): string => ('text' in p ? p.text : 'made' in p ? `<${p.made}>` : `<${p.manifest}.manifest>`)

function recordingName(r: Recording): string {
  if ('runtimeId' in r) return 'runtime_id'
  if ('clientSecret' in r) return 'client_secret'
  return 'secretId' in r ? 'secret_id' : 'unchanged'
}

const rendered = (s: Step) => ({ handle: s.handle, action: s.action, records: recordingName(s.records), argv: s.argv.map(word) })

describe('16.10.26ad AC15 the plan vectors hold', () => {
  it('has a vector for every rule', () => expect(VECTORS.length).toBeGreaterThanOrEqual(8))

  for (const v of VECTORS) {
    it(v.name, () => {
      const def = readDefinition(v.definition)
      const rows = live(v.live)
      const kept = stillLive(def, records(v.recorded), rows)
      if (v.expect.refused !== undefined) {
        expect(() => buildPlan(def, kept)).toThrow(Refusal)
        const refusal = (() => {
          try {
            buildPlan(def, kept)
          } catch (e) {
            return e as Refusal
          }
        })()
        expect([refusal?.why, refusal?.handle]).toEqual(['script', v.expect.refused])
        return
      }
      const ops = buildPlan(def, kept)
      expect(Object.fromEntries(kept)).toEqual(v.expect.kept)
      expect(JSON.parse(JSON.stringify(ops))).toEqual(v.expect.ops)
      if (v.verdicts === undefined) return
      if (v.stop !== undefined) {
        const stop = (() => {
          try {
            steps(ops, v.verdicts ?? [], rows, kept)
          } catch (e) {
            return e as Stop
          }
        })()
        expect(stop).toBeInstanceOf(Stop)
        expect(stop?.replace === undefined ? { refused: stop?.refused.map(([h]) => h) } : { replace: stop.replace }).toEqual(v.stop)
        return
      }
      expect(steps(ops, v.verdicts, rows, kept).map(rendered)).toEqual(v.steps)
    })
  }

  it('quotes a shell word only where it needs it', () => {
    const parts: Part[] = [{ text: 'oauth_client' }, { text: '--name' }, { text: "My server's" }, { made: 'n1' }]
    expect(shellLine(parts, () => '')).toBe("lingara oauth_client --name 'My server'\\''s' '<n1>'")
  })
})
