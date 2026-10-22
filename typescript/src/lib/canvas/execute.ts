// D7 steps 4–7. Each step's argv is dispatched **in-process** through the
// app's `run`, `main`'s own entry point, with `--json`, so no credential
// crosses a process boundary and no second token exchange happens. Apply
// records each object's `runtime_id` (a secret's id), never its platform
// object id, then activates `complete`, or `partial` at the first failure.
import { message } from '../assets.js'
import type { Ctx } from '../ctx.js'
import { Exit, Fail, failure } from '../fail.js'
import { render } from '../output.js'
import { APP, get, type Kind, OAUTH_CLIENT, type Obj, str, WEBHOOK_OUTBOUND } from '../platform/objects.js'
import { fixed } from '../routes.js'
import type { Planned } from './apply.js'
import type { Part, Step } from './argv.js'
import type { Records } from './plan.js'

/** A `runtime_id` still `null` is re-read once a second, ten times (D7). */
const PUBLISH_POLLS = 10

const kindOf = (nodeKind: string): Kind => (nodeKind === 'client' ? OAUTH_CLIENT : nodeKind === 'webhook' ? WEBHOOK_OUTBOUND : APP)

const NULL_SINK = { write: () => {} }

class Run {
  recorded: Records
  /** Handle → the platform object id an earlier step made. */
  made = new Map<string, string>()
  /** Handle → the secret its create minted. */
  minted = new Map<string, string>()
  secrets: Obj[] = []
  rows: string[][] = []

  constructor(kept: Records) {
    this.recorded = new Map(kept)
  }

  resolve(argv: Part[], manifests: Map<string, string>): string[] {
    return argv.map((p) => {
      const word = 'text' in p ? p.text : 'made' in p ? this.made.get(p.made) : manifests.get(p.manifest)
      if (word === undefined) throw failure(`${'made' in p ? p.made : ''} was not made before it was needed`)
      return word
    })
  }

  /** One inner verb; its JSON, any secret it revealed kept even when it failed part-way. */
  async invoke(ctx: Ctx, handle: string, argv: string[]): Promise<Obj> {
    const outcome = await ctx.app.run([...argv, '--json', '--profile', ctx.profile], NULL_SINK)
    let answer: Obj = {}
    try {
      answer = JSON.parse(outcome.stdout) as Obj
    } catch {
      // A failed verb may print nothing; its fail says why.
    }
    const s = answer.secret as Obj | undefined
    const secret = s !== undefined ? { handle, id: s.id, value: s.value } : typeof answer.secret_id === 'string' ? { handle, id: answer.secret_id, value: answer.secret } : undefined
    if (secret !== undefined) {
      this.minted.set(handle, str(secret.id))
      this.secrets.push(secret)
    }
    if (outcome.fail !== undefined) throw outcome.fail
    return answer
  }

  async step(ctx: Ctx, step: Step, manifests: Map<string, string>): Promise<void> {
    const r = step.records
    if ('clientSecret' in r) {
      const id = this.minted.get(r.clientSecret)
      if (id === undefined) throw failure(`${r.clientSecret} minted no secret`)
      for (const s of this.secrets) if (s.handle === r.clientSecret) s.handle = step.handle
      this.recorded.set(step.handle, id)
    } else if (step.argv.length > 0) {
      const answer = await this.invoke(ctx, step.handle, this.resolve(step.argv, manifests))
      if ('runtimeId' in r) {
        this.made.set(step.handle, str(answer.id))
        this.recorded.set(step.handle, await runtimeId(ctx, kindOf(step.kind), str(answer.id), answer))
      } else if ('secretId' in r) {
        this.recorded.set(step.handle, str(answer.secret_id))
      }
    }
    this.rows.push([step.handle, step.kind, step.action, this.recorded.get(step.handle) ?? '-'])
  }

  /** D7 step 7: `HANDLE  KIND  ACTION  ID`, then every new secret, once. */
  report(ctx: Ctx, status: string): void {
    const objects = Object.fromEntries([...this.recorded].map(([h, id]) => [h, { id }]))
    if (ctx.out.json) {
      ctx.out.jsonValue({ objects, secrets: this.secrets, status })
    } else {
      let text = render(['HANDLE', 'KIND', 'ACTION', 'ID'], this.rows)
      if (this.secrets.length > 0) text += `\n${render(['HANDLE', 'SECRET_ID', 'SECRET'], this.secrets.map((s) => [str(s.handle), str(s.id), str(s.value)]))}`
      ctx.out.print(text)
    }
    if (this.secrets.length > 0) ctx.out.note('Each secret is shown once: store them now. They cannot be read again.')
  }
}

/** The object's `runtime_id`, set once publishing has projected (16.10.26v §5). */
async function runtimeId(ctx: Ctx, k: Kind, platformId: string, answer: Obj): Promise<string> {
  let current = answer
  for (let i = 0; i < PUBLISH_POLLS; i += 1) {
    if (typeof current.runtime_id === 'string') return current.runtime_id
    await ctx.app.http.clock.sleep(1)
    current = await get(ctx, k, platformId)
  }
  if (typeof current.runtime_id === 'string') return current.runtime_id
  throw new Fail(Exit.unavailable, 'object_projection_pending', `${k.word} ${platformId} is still publishing; nothing after it was applied`)
}

/** `{draft_version, outcome, objects}`: runtime ids only (16.10.26x D4). */
async function activate(ctx: Ctx, planned: Planned, recorded: Records, outcome: string): Promise<void> {
  const objects = Object.fromEntries([...recorded].map(([h, id]) => [h, { id }]))
  const body = { json: { draft_version: planned.draftVersion, outcome, objects } }
  try {
    await ctx.call({ route: fixed('canvas.activate'), vars: { id: planned.canvasId }, body })
  } catch (e) {
    if (e instanceof Fail && e.code === 'canvas_conflict') e.hint(`the canvas changed while applying; run \`lingara canvas get ${planned.canvasId}\``)
    throw e
  }
}

export async function execute(ctx: Ctx, planned: Planned, all: Step[], manifests: Map<string, string>): Promise<void> {
  const run = new Run(planned.kept)
  for (const [done, step] of all.entries()) {
    try {
      await run.step(ctx, step, manifests)
    } catch (e) {
      const fail = e instanceof Fail ? e : failure(String(e))
      const saved = await activate(ctx, planned, run.recorded, 'partial').then(() => undefined, (f: Fail) => f)
      run.report(ctx, 'partial')
      if (saved !== undefined) fail.hint(`the partial record was not saved: ${saved.message} (${saved.code})`)
      throw fail.hint(message('canvas_apply_partial', { done: String(done), total: String(all.length), id: planned.canvasId }))
    }
  }
  await activate(ctx, planned, run.recorded, 'complete')
  run.report(ctx, 'complete')
}
