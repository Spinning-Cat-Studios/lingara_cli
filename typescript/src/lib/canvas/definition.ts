// A canvas definition (Backend 2.10.26e §2), as far as apply reads it, and
// the order the web's `flowDefinition.ts` walks it in.

export interface FlowNode {
  id: string
  kind: string
  fields: Record<string, unknown>
  /** A script node: it runs only in the browser's sandbox (2.10.26b D5). */
  custom: boolean
}

export interface Definition {
  nodes: FlowNode[]
  edges: [string, string][]
}

const rec = (v: unknown): Record<string, unknown> => (typeof v === 'object' && v !== null && !Array.isArray(v) ? (v as Record<string, unknown>) : {})
const str = (v: unknown): string => (typeof v === 'string' ? v : '')

export function readDefinition(value: unknown): Definition {
  const d = rec(value)
  const nodes = (Array.isArray(d.nodes) ? d.nodes : []).map(rec)
  const edges = (Array.isArray(d.edges) ? d.edges : []).map(rec)
  return {
    nodes: nodes.map((n) => ({ id: str(n.id), kind: str(n.kind), fields: rec(n.fields), custom: n.custom === true || typeof n.script === 'string' })),
    edges: edges.map((e) => [str(e.from), str(e.to)] as [string, string]),
  }
}

/** Kahn's algorithm, ties broken by node order, as `topoOrder`; `undefined` on a cycle. */
export function topoOrder(def: Definition): FlowNode[] | undefined {
  const indegree = new Map(def.nodes.map((n) => [n.id, def.edges.filter(([, to]) => to === n.id).length]))
  const done = new Set<string>()
  const order: FlowNode[] = []
  while (order.length < def.nodes.length) {
    const next = def.nodes.find((n) => !done.has(n.id) && indegree.get(n.id) === 0)
    if (next === undefined) return undefined
    order.push(next)
    done.add(next.id)
    for (const [from, to] of def.edges) if (from === next.id) indegree.set(to, (indegree.get(to) ?? 0) - 1)
  }
  return order
}

/** A dependent node's client: its one direct upstream `client` node, or `undefined` for zero or two. */
export function clientOf(def: Definition, handle: string): string | undefined {
  const parents = def.nodes.filter((n) => n.kind === 'client' && def.edges.some(([from, to]) => from === n.id && to === handle))
  return parents.length === 1 ? parents[0].id : undefined
}
