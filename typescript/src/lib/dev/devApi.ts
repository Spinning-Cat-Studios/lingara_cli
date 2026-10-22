// The five `app_dev.*` routes (Backend 16.10.26v §12; ADR 16.10.26ae
// R1–R3), one function each, plus the read-then-patch every writer of the dev
// version uses: a `409 version_conflict` re-reads once and retries, and a
// second one is the server's `refused`.
import type { Ctx } from '../ctx.js'
import { Fail } from '../fail.js'
import type { Body } from '../http.js'
import { type FixedName, fixed } from '../routes.js'

type Obj = Record<string, unknown>

/** What an open answers: the session, and the secrets it minted, which live only in this process's memory and the app's environment (D5). */
export interface Session {
  id: string
  leaseSecs: number
  expiresAt: string
  appSecret: string
  clientSecret?: string
}

const call = async (ctx: Ctx, name: FixedName, vars: Record<string, string>, body?: Body): Promise<Obj> =>
  (await ctx.call({ route: fixed(name), vars, body })) as Obj

/** `GET …/dev`: the manifest, `render_url`, `draft_version`, the session. */
export const get = (ctx: Ctx, id: string): Promise<Obj> => call(ctx, 'app_dev.get', { id })
export const patch = (ctx: Ctx, id: string, body: Obj): Promise<Obj> => call(ctx, 'app_dev.patch', { id }, { json: body })

/** Reads `draft_version`, then patches `build(draft_version)`; one re-read and retry on `version_conflict`. */
export async function patchWithRetry(ctx: Ctx, id: string, build: (draftVersion: unknown) => Obj): Promise<Obj> {
  const draft = (await get(ctx, id)).draft_version
  try {
    return await patch(ctx, id, build(draft))
  } catch (e) {
    if (!(e instanceof Fail) || e.code !== 'version_conflict') throw e
    return patch(ctx, id, build((await get(ctx, id)).draft_version))
  }
}

const text = (v: unknown): string => (typeof v === 'string' ? v : '')
const secretOf = (v: unknown): string | undefined => {
  const s = (v as Obj | null | undefined)?.secret
  return typeof s === 'string' ? s : undefined
}

/** `POST …/dev/session {take_over, credentials}`. */
export async function open(ctx: Ctx, id: string, takeOver: boolean, client: boolean): Promise<Session> {
  const body: Obj = { take_over: takeOver }
  if (client) body.credentials = ['client']
  const answer = await call(ctx, 'app_dev.session_open', { id }, { json: body })
  const lease = typeof answer.lease_secs === 'number' && answer.lease_secs > 0 ? answer.lease_secs : 300
  return { id: text(answer.session_id), leaseSecs: lease, expiresAt: text(answer.expires_at), appSecret: secretOf(answer.app_secret) ?? '', clientSecret: secretOf(answer.client_secret) }
}

export const renew = (ctx: Ctx, id: string, sessionId: string): Promise<Obj> => call(ctx, 'app_dev.session_renew', { id, session_id: sessionId })

/** `DELETE …/dev/session/{sid}`: parks, keeps `render_url`, revokes the session's credentials. */
export const end = (ctx: Ctx, id: string, sessionId: string): Promise<Obj> => call(ctx, 'app_dev.session_end', { id, session_id: sessionId })
