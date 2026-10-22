// D7 step 2: the web planner's rules (`plan.ts`) over field nodes.
//
// Ops come out in topological order and every client reference is a
// backward `{handle}`. "Still live" means live **and** still on the recorded
// client: a recorded client is live when the client list has a row with its
// `runtime_id`; a webhook or app when its list has its `runtime_id` under
// that client's platform object; a secret when its client is (the platform
// lists carry no secrets, and a recorded secret is never re-planned). A node
// with `custom: true` runs a script, which only the browser may do, so it
// refuses the plan by its handle.
import { clientOf, type Definition, type FlowNode, topoOrder } from './definition.js'
import { ephemeral } from '../dev/manifest.js'
import { appBody, clientBody, webhookBody } from './spec.js'

/** Node handle → the runtime id an earlier apply recorded for it. */
export type Records = Map<string, string>

/** One `/platform/objects` list row, as the planner joins it. */
export interface Row {
  id: string
  runtime_id: string
  parent_id: string
}

const s = (v: unknown): string => (typeof v === 'string' ? v : '')
export const rowOf = (v: unknown): Row => {
  const r = (v ?? {}) as Record<string, unknown>
  return { id: s(r.id), runtime_id: s(r.runtime_id), parent_id: s(r.parent_id) }
}

/** The live `oauth_client`, `webhook_out` and `app` lists (D7 step 1). */
export interface Live {
  clients: Row[]
  webhooks: Row[]
  apps: Row[]
}

const rowsOf = (live: Live, kind: string): Row[] => (kind === 'client' ? live.clients : kind === 'webhook' ? live.webhooks : live.apps)

/** The platform object id behind a recorded runtime id of `kind`. */
export const platformId = (live: Live, kind: string, runtimeId: string): string | undefined => rowsOf(live, kind).find((r) => r.runtime_id === runtimeId)?.id

/** `{handle, kind, op, client?: {handle}, id?, body?}`, the dry-run's shape. */
export interface PlanOp {
  handle: string
  kind: string
  op: 'create' | 'update'
  client?: { handle: string }
  id?: string
  body?: Record<string, unknown>
}

export class Refusal extends Error {
  constructor(
    // `ephemeral`: an app node whose manifest renders from a quick tunnel or
    // `dev.invalid` (16.10.26ad D7 step 2, 16.10.26ae D10 row 15).
    public why: 'script' | 'fields' | 'no_client' | 'cycle' | 'ephemeral',
    public handle: string,
    detail: string,
  ) {
    super(detail)
  }
}

function dependantLive(node: FlowNode, record: string, clientPlatformId: string, live: Live): boolean {
  if (node.kind === 'secret') return true
  return rowsOf(live, node.kind).some((r) => r.runtime_id === record && r.parent_id === clientPlatformId)
}

/** The recorded entries still live by the rule above. */
export function stillLive(def: Definition, recorded: Records, live: Live): Records {
  const kept: Records = new Map()
  for (const node of topoOrder(def) ?? []) {
    const record = recorded.get(node.id)
    if (record === undefined) continue
    const clientRecord = node.kind === 'client' ? undefined : kept.get(clientOf(def, node.id) ?? '')
    const client = live.clients.find((c) => c.runtime_id === (node.kind === 'client' ? record : clientRecord))
    const keep = node.kind === 'client' ? client !== undefined : client !== undefined && dependantLive(node, record, client.id, live)
    if (keep) kept.set(node.id, record)
  }
  return kept
}

function spelled(node: FlowNode, create: boolean): Record<string, unknown> | undefined {
  try {
    if (node.kind === 'client') return clientBody(node.fields, create)
    if (node.kind === 'webhook') return webhookBody(node.fields)
    if (node.kind === 'app') return appBody(node.fields)
    return undefined
  } catch (e) {
    throw new Refusal('fields', node.id, (e as Error).message)
  }
}

function bodyFor(node: FlowNode, create: boolean): Record<string, unknown> | undefined {
  const body = spelled(node, create)
  const renderUrl = (body?.manifest as { render_url?: unknown } | undefined)?.render_url
  if (node.kind === 'app' && ephemeral(typeof renderUrl === 'string' ? renderUrl : '')) throw new Refusal('ephemeral', node.id, 'renders from an ephemeral URL')
  return body
}

/** One node's op: `create` without a kept record, `update` with one; a dependant names its one client. */
function opFor(def: Definition, node: FlowNode, record: string | undefined): PlanOp {
  const client = node.kind === 'client' ? undefined : clientOf(def, node.id)
  if (node.kind !== 'client' && client === undefined) throw new Refusal('no_client', node.id, 'needs exactly one client node upstream')
  const op: PlanOp = { handle: node.id, kind: node.kind, op: record === undefined ? 'create' : 'update' }
  if (client !== undefined && op.op === 'create') op.client = { handle: client }
  if (record !== undefined) op.id = record
  const body = bodyFor(node, op.op === 'create')
  if (body !== undefined) op.body = body
  return op
}

/** D7 step 2: every field node's op, in plan order, over the kept records. */
export function buildPlan(def: Definition, kept: Records): PlanOp[] {
  const order = topoOrder(def)
  if (order === undefined) throw new Refusal('cycle', '', 'the canvas has a cycle')
  const scripted = order.find((n) => n.custom)
  if (scripted !== undefined) throw new Refusal('script', scripted.id, 'runs a script')
  // A recorded secret is never re-planned.
  return order.filter((n) => !(n.kind === 'secret' && kept.has(n.id))).map((n) => opFor(def, n, kept.get(n.id)))
}
