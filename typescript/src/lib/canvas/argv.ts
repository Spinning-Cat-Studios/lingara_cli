// D7 steps 3–4: the dry-run's verdicts over the plan become this CLI's own
// command lines. A `refused` verdict stops apply with every refusal named; a
// `would_replace`, which the token door no longer sends, stops it before any
// write. A `would_update` webhook carries exactly the flags its `changes`
// name: `--url`/`--event` (a new version that keeps the endpoint and its
// secret) and `--enable` (no builder spec disables one).
import { platformId, type Live, type PlanOp, type Records } from './plan.js'
import { appName } from './spec.js'

/** A word of an argv: text, an id already known, one an earlier step makes, or an app op's manifest file. */
export type Part = { text: string } | { made: string } | { manifest: string }

/** What apply records for a step's node (16.10.26x D4: runtime ids only). */
export type Recording = { runtimeId: true } | { clientSecret: string } | { secretId: true } | { unchanged: true }

export interface Step {
  handle: string
  kind: string
  action: string
  /** Empty: no call. */
  argv: Part[]
  records: Recording
}

export type Verdict = Record<string, unknown>

export class Stop extends Error {
  constructor(
    public refused: [string, string, string][],
    public replace?: string,
  ) {
    super(replace === undefined ? 'refused' : 'would_replace')
  }
}

const t = (text: string): Part => ({ text })
const s = (v: unknown): string => (typeof v === 'string' ? v : '')
const strings = (v: unknown): string[] => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : [])
const repeated = (flag: string, values: string[]): Part[] => values.flatMap((v) => [t(flag), t(v)])

/** Every step, or why apply must stop before writing anything. */
export function steps(ops: PlanOp[], verdicts: Verdict[], live: Live, kept: Records): Step[] {
  const refused = verdicts.filter((v) => v.verdict === 'refused').map((v): [string, string, string] => [s(v.handle), s(v.code), s(v.error)])
  if (refused.length > 0) throw new Stop(refused)
  const replace = verdicts.find((v) => v.verdict === 'would_replace')
  if (replace !== undefined) throw new Stop([], s(replace.handle))
  return ops.map((op) => step(op, verdicts.find((v) => v.handle === op.handle) ?? {}, live, kept, ops))
}

/** The client an op points at: made in this run, or a recorded one's platform object id. */
function clientRef(handle: string, live: Live, kept: Records, ops: PlanOp[]): Part {
  const madeHere = ops.some((o) => o.handle === handle && o.op === 'create')
  const known = kept.has(handle) ? platformId(live, 'client', kept.get(handle) as string) : undefined
  return !madeHere && known !== undefined ? t(known) : { made: handle }
}

function step(op: PlanOp, verdict: Verdict, live: Live, kept: Records, ops: PlanOp[]): Step {
  const changes = strings(verdict.changes)
  const unchanged = op.op === 'update' && changes.length === 0
  const ownId = op.id === undefined ? undefined : platformId(live, op.kind, op.id)
  const own: Part = ownId === undefined ? { made: op.handle } : t(ownId)
  const client = op.client === undefined ? { made: '' } : clientRef(op.client.handle, live, kept, ops)
  const base = { handle: op.handle, kind: op.kind, action: unchanged ? 'unchanged' : op.op }
  if (unchanged) return { ...base, argv: [], records: { unchanged: true } }
  const body = op.body ?? {}
  const [argv, records] = argvFor(op, verdict, { body, own, client, changes })
  return { ...base, argv, records }
}

interface Pieces {
  body: Record<string, unknown>
  own: Part
  client: Part
  changes: string[]
}

function clientArgv(op: PlanOp, p: Pieces): Part[] {
  const head = op.op === 'create' ? [t('oauth_client'), t('create')] : [t('oauth_client'), t('update'), p.own]
  return [...head, t('--name'), t(s(p.body.name)), ...repeated('--scope', strings(p.body.scopes)), ...repeated('--redirect-uri', strings(p.body.redirect_uris))]
}

function argvFor(op: PlanOp, verdict: Verdict, p: Pieces): [Part[], Recording] {
  const created: Recording = op.op === 'create' ? { runtimeId: true } : { unchanged: true }
  if (op.kind === 'client') return [clientArgv(op, p), created]
  if (op.kind === 'secret') {
    if (verdict.note === 'minted_with_client') return [[], { clientSecret: op.client?.handle ?? '' }]
    return [[t('oauth_client'), t('secret'), t('create'), p.client], { secretId: true }]
  }
  if (op.kind === 'webhook') return [op.op === 'create' ? webhookCreate(p) : webhookUpdate(p), created]
  const manifest: Part = { manifest: op.handle }
  if (op.op === 'create') return [[t('apps'), t('create'), t(appName(p.body, op.handle)), t('--client'), p.client, t('--manifest'), manifest], created]
  return [[t('apps'), t('update'), p.own, t('--manifest'), manifest], created]
}

const webhookCreate = (p: Pieces): Part[] => [t('webhook'), t('outbound'), t('create'), t('--client'), p.client, t('--url'), t(s(p.body.url)), ...repeated('--event', strings(p.body.event_types))]

function webhookUpdate(p: Pieces): Part[] {
  const argv: Part[] = [t('webhook'), t('outbound'), t('update'), p.own]
  if (p.changes.includes('url')) argv.push(t('--url'), t(s(p.body.url)))
  if (p.changes.includes('event_types')) argv.push(...repeated('--event', strings(p.body.event_types)))
  if (p.changes.includes('enabled')) argv.push(t('--enable'))
  return argv
}

/** POSIX single quotes, only where a word needs them. */
export const quote = (word: string): string => (word !== '' && /^[A-Za-z0-9\-_./:@=,+]+$/.test(word) ? word : `'${word.replace(/'/g, "'\\''")}'`)

/** One argv as a shell line, ids not yet made as `<handle>`. */
export function shellLine(argv: Part[], manifest: (handle: string) => string): string {
  const words = argv.map((p) => ('text' in p ? quote(p.text) : 'made' in p ? quote(`<${p.made}>`) : quote(manifest(p.manifest))))
  return `lingara ${words.join(' ')}`
}
