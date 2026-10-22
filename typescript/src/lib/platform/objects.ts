// The `/platform/objects` family every kind shares (16.10.26v §8; D2): list
// every page, read, rename, create and publish, the body update that
// respects the one editable version, delete with the typed email, and the
// one-time secret mint.
import type { Ctx } from '../ctx.js'
import type { Body } from '../http.js'
import { object, type Op } from '../routes.js'

export type Obj = Record<string, unknown>

/** A CLI word and the 16.10.26v kind behind it. */
export interface Kind {
  /** `routes.toml`'s word: `webhook_outbound`. */
  word: string
  /** The platform kind: `webhook_out`. */
  kind: string
  /** `{kind}` in `confirm_yes_no`. */
  label: string
}

export const OAUTH_CLIENT: Kind = { word: 'oauth_client', kind: 'oauth_client', label: 'OAuth client' }
export const APP: Kind = { word: 'app', kind: 'app', label: 'app' }
export const EMBED: Kind = { word: 'embed', kind: 'embed', label: 'embed' }
export const WEBHOOK_INBOUND: Kind = { word: 'webhook_inbound', kind: 'webhook_in', label: 'inbound webhook' }
export const WEBHOOK_OUTBOUND: Kind = { word: 'webhook_outbound', kind: 'webhook_out', label: 'outbound webhook' }
export const CANVAS: Kind = { word: 'canvas', kind: 'canvas', label: 'canvas' }

export const str = (v: unknown): string => (typeof v === 'string' ? v : '')

async function send(ctx: Ctx, k: Kind, op: Op, vars: Record<string, string>, body?: Body): Promise<Obj> {
  return (await ctx.call({ route: object(k.word, op), vars, body })) as Obj
}

/** Every item of every page, followed by `next_cursor` (B1). */
export async function pages(fetchPage: (cursor?: string) => Promise<Obj>): Promise<unknown[]> {
  const items: unknown[] = []
  for (let cursor: string | undefined; ; ) {
    const page = await fetchPage(cursor)
    items.push(...((page.items as unknown[] | undefined) ?? []))
    if (typeof page.next_cursor !== 'string') return items
    cursor = page.next_cursor
  }
}

export function list(ctx: Ctx, k: Kind): Promise<unknown[]> {
  const route = object(k.word, 'list')
  return pages(async (cursor) => {
    const query: [string, string][] = [['kind', k.kind], ...(cursor === undefined ? [] : [['cursor', cursor] as [string, string]])]
    return (await ctx.call({ route, query })) as Obj
  })
}

export const get = (ctx: Ctx, k: Kind, id: string): Promise<Obj> => send(ctx, k, 'get', { id })
export const rename = (ctx: Ctx, k: Kind, id: string, name: string): Promise<Obj> => send(ctx, k, 'rename', { id }, { json: { name } })
export const versionOp = (ctx: Ctx, k: Kind, op: Op, id: string, semver: string): Promise<Obj> => send(ctx, k, op, { id, semver })
export const secretCreate = (ctx: Ctx, k: Kind, id: string): Promise<Obj> => send(ctx, k, 'secret_create', { id })

/** The object and its first draft: `{kind, name, parent_id?, body}`. */
export function create(ctx: Ctx, k: Kind, name: string, parent: string | undefined, body: unknown): Promise<Obj> {
  const request: Obj = { kind: k.kind, name, body }
  if (parent !== undefined) request.parent_id = parent
  return send(ctx, k, 'create', {}, { json: request })
}

/** A private object skips review, so a version is published at once (16.10.26v §4). */
export async function publishIfPrivate(ctx: Ctx, k: Kind, obj: Obj, semver: string): Promise<Obj | undefined> {
  if (obj.visibility === 'public' || semver === '') return undefined
  return versionOp(ctx, k, 'publish', str(obj.id), semver)
}

/**
 * D2's body update: overlay the flags on the one editable version
 * (`version_put` at its `draft_version`), else on the serving (or highest)
 * version as a new one (`version_create`). Always a whole body.
 */
export async function bodyUpdate(ctx: Ctx, k: Kind, id: string, overlay: (body: Obj) => void): Promise<Obj> {
  const current = await get(ctx, k, id)
  const versions = (current.versions as Obj[] | undefined) ?? []
  const editable = versions.find((v) => v.state === 'draft' || v.state === 'changes_requested')
  let answer: Obj
  if (editable !== undefined) {
    const version = await versionOp(ctx, k, 'version_get', id, str(editable.semver))
    const edit = { body: overlaid(version.body, overlay), draft_version: version.draft_version }
    answer = await send(ctx, k, 'version_put', { id, semver: str(editable.semver) }, { json: edit })
  } else {
    const base = str(current.serving_version) || str(versions[0]?.semver)
    const start = base === '' ? {} : (await versionOp(ctx, k, 'version_get', id, base)).body
    answer = await send(ctx, k, 'version_create', { id }, { json: { body: overlaid(start, overlay) } })
  }
  return (await publishIfPrivate(ctx, k, current, str(answer.semver))) ?? answer
}

function overlaid(body: unknown, overlay: (body: Obj) => void): Obj {
  const out: Obj = typeof body === 'object' && body !== null && !Array.isArray(body) ? { ...(body as Obj) } : {}
  overlay(out)
  return out
}

/** The destructive request: the canonical email in its body (B3.6). */
export function destroy(ctx: Ctx, k: Kind, id: string, version: string | undefined, email: string): Promise<Obj> {
  const body = { json: { confirm: email } }
  return version === undefined ? send(ctx, k, 'delete', { id }, body) : send(ctx, k, 'delete_version', { id, semver: version }, body)
}

/** A list's human columns, each a heading and the member it reads. */
export type Columns = [string, string][]

export async function printList(ctx: Ctx, k: Kind, columns: Columns): Promise<void> {
  ctx.out.list(await list(ctx, k), columns)
}

export async function printGet(ctx: Ctx, k: Kind, id: string): Promise<void> {
  ctx.out.value(await get(ctx, k, id))
}
