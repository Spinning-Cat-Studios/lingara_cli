// `canvas apply <id>`, D7 steps 1–3, and the two flags that stop there:
// `--dry-run` prints the verdicts, `--print-commands` the argvs. Neither
// writes. Steps 4–7 are `execute.ts`'s.
import { rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

import type { Ctx } from '../ctx.js'
import { Exit, Fail, fromMessage } from '../fail.js'
import { APP, list, OAUTH_CLIENT, type Obj, WEBHOOK_OUTBOUND } from '../platform/objects.js'
import { fixed } from '../routes.js'
import { shellLine, type Step, steps, Stop, type Verdict } from './argv.js'
import { readDefinition } from './definition.js'
import { execute } from './execute.js'
import { buildPlan, type Live, type PlanOp, type Records, Refusal, rowOf, stillLive } from './plan.js'

/** What one apply works over: the draft's version, its kept record, the live lists and the plan. */
export interface Planned {
  canvasId: string
  draftVersion: number
  kept: Records
  live: Live
  ops: PlanOp[]
}

const refused = (message: string): Fail => new Fail(Exit.refused, 'canvas_apply_refused', message)

export const getCanvas = async (ctx: Ctx, id: string): Promise<Obj> => (await ctx.call({ route: fixed('canvas.get'), vars: { id } })) as Obj

/** The active record overlaid by the draft's, kept to the draft's nodes (`recordedObjects`). */
function recorded(canvas: Obj, handles: string[]): Records {
  const all: Records = new Map()
  for (const side of ['active', 'draft']) {
    const objects = ((canvas[side] as Obj | null)?.objects ?? {}) as Record<string, { id?: unknown }>
    for (const [h, v] of Object.entries(objects)) if (typeof v.id === 'string') all.set(h, v.id)
  }
  return new Map([...all].filter(([h]) => handles.includes(h)))
}

function refusalFail(r: Refusal): Fail {
  if (r.why === 'ephemeral') return fromMessage(Exit.renderUrlEphemeral, 'render_url_ephemeral', { file: `node ${r.handle}'s manifest` })
  if (r.why === 'script') return refused(`node ${r.handle} runs a script; apply this canvas in the browser`)
  if (r.why === 'fields') return refused(`node ${r.handle}'s fields are not one the builder can apply: ${r.message}`)
  if (r.why === 'no_client') return refused(`node ${r.handle} needs exactly one client node upstream`)
  return refused("the canvas's edges form a cycle")
}

/** D7 steps 1–2: the canvas and the three lists, every page, then the plan. */
export async function plan(ctx: Ctx, id: string): Promise<Planned> {
  const canvas = await getCanvas(ctx, id)
  const draft = canvas.draft as Obj | null | undefined
  if (draft === null || draft === undefined) throw refused('nothing to apply: save a draft with `lingara canvas draft`')
  const live: Live = {
    clients: (await list(ctx, OAUTH_CLIENT)).map(rowOf),
    webhooks: (await list(ctx, WEBHOOK_OUTBOUND)).map(rowOf),
    apps: (await list(ctx, APP)).map(rowOf),
  }
  const definition = readDefinition(draft.definition)
  const kept = stillLive(definition, recorded(canvas, definition.nodes.map((n) => n.id)), live)
  let ops: PlanOp[]
  try {
    ops = buildPlan(definition, kept)
  } catch (e) {
    throw e instanceof Refusal ? refusalFail(e) : e
  }
  return { canvasId: id, draftVersion: typeof draft.draft_version === 'number' ? draft.draft_version : 0, kept, live, ops }
}

function stopFail(stop: Stop): Fail {
  if (stop.replace !== undefined) return refused(`node ${stop.replace}: unexpected \`would_replace\` from the server; nothing was applied`)
  const fail = refused(`the dry-run refused ${stop.refused.length} op(s); nothing was applied`)
  for (const [h, code, error] of stop.refused) fail.hint(`${h}: ${error} (${code})`)
  return fail
}

/** Each app op's manifest, as the file its `--manifest` names. */
function writeManifests(ops: PlanOp[]): Map<string, string> {
  const out = new Map<string, string>()
  for (const op of ops.filter((o) => o.kind === 'app')) {
    const path = join(tmpdir(), `lingara-${process.pid}-${op.handle}.manifest.json`)
    writeFileSync(path, JSON.stringify(op.body?.manifest ?? null))
    out.set(op.handle, path)
  }
  return out
}

/** The matryoshka made visible: what apply would run, as shell lines. */
function printCommands(ctx: Ctx, all: Step[], manifests: Map<string, string>): void {
  const lines = all.filter((s) => s.argv.length > 0).map((s) => shellLine(s.argv, (h) => manifests.get(h) ?? ''))
  if (ctx.out.json) ctx.out.jsonValue(lines)
  else for (const line of lines) ctx.out.print(`${line}\n`)
}

export async function apply(ctx: Ctx, id: string, mode: { dryRun: boolean; printCommands: boolean }): Promise<void> {
  const planned = await plan(ctx, id)
  const body = { json: { ops: planned.ops } }
  const answer = (await ctx.call({ route: fixed('canvas.dry_run'), body })) as Obj
  const verdicts = (answer.verdicts as Verdict[] | undefined) ?? []
  if (mode.dryRun) {
    if (ctx.out.json) ctx.out.jsonValue(answer)
    else ctx.out.list(verdicts, [['HANDLE', 'handle'], ['KIND', 'kind'], ['OP', 'op'], ['VERDICT', 'verdict'], ['CODE', 'code']])
    if (answer.ok === false) throw refused('the dry-run refused at least one op')
    return
  }
  let all: Step[]
  try {
    all = steps(planned.ops, verdicts, planned.live, planned.kept)
  } catch (e) {
    throw e instanceof Stop ? stopFail(e) : e
  }
  const manifests = writeManifests(planned.ops)
  if (mode.printCommands) {
    printCommands(ctx, all, manifests)
    return
  }
  try {
    await execute(ctx, planned, all, manifests)
  } finally {
    for (const path of manifests.values()) rmSync(path, { force: true })
  }
}
