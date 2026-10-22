// An `apps dev` session (ADR 16.10.26ae D7 steps 3, 4 and 8): open it, start
// the app with the session's credentials, hand over to `devHold`, then end in
// order — `DELETE` the session, stop cloudflared, stop the app's group — each
// step running even if the one before it failed. Under `--json`, stdout
// carries one value, written here at exit.
import { writeFileSync } from 'node:fs'

import { childEnv, Group, relay, waitListening } from '../../supervise.js'
import type { Tunnel } from '../../tunnel/cloudflared.js'
import { message } from '../assets.js'
import type { Ctx } from '../ctx.js'
import { Exit, exitName, Fail, failure, fromMessage } from '../fail.js'
import { APP, get as getObject, str } from '../platform/objects.js'
import { end, open, type Session } from './devApi.js'
import { firstUrl, hold } from './devHold.js'
import type { Plan } from './plan.js'

const LISTEN_WAIT_MS = 60_000
const STOP_GRACE_MS = 5_000
const APP_PREFIX = 'app │ '

/** The children and what the session pointed at, for step 8 and the report. */
export interface Run {
  app?: Group
  tunnel?: Tunnel
  urls: string[]
  /** The session ended under us (taken over, expired): no `DELETE`. */
  lost: boolean
  respawns: number
  stopped: () => boolean
}

export const interrupted = (): Fail => new Fail(Exit.interrupted, 'interrupted', 'interrupted')
export const lost = (name: string): Fail => fromMessage(Exit.devSessionLost, 'dev_session_lost', { name })

/** `409 dev_session_held` re-worded with the app's name and the lease. */
function held(e: unknown, name: string): unknown {
  if (!(e instanceof Fail) || e.exit !== Exit.devSessionHeld) return e
  let lease = 'its lease ends'
  try {
    const body = JSON.parse(e.body ?? '{}') as { lease_expires_at?: unknown }
    if (typeof body.lease_expires_at === 'string') lease = body.lease_expires_at
  } catch {
    // The server's text stands.
  }
  return fromMessage(Exit.devSessionHeld, 'dev_session_held', { name, lease_expires_at: lease })
}

/** Ctrl+C and SIGTERM, as a flag the session polls. */
function signals(): { stopped: () => boolean; release: () => void } {
  let flag = false
  const on = (): void => {
    flag = true
  }
  process.on('SIGINT', on).on('SIGTERM', on)
  return { stopped: () => flag, release: () => process.off('SIGINT', on).off('SIGTERM', on) }
}

/** What the app's environment gains (D5, D7 step 4): the row's `env` and `env_file`, the session's credentials, and the port. */
function sessionVars(plan: Plan, session: Session, clientId: string | undefined): [string, string][] {
  const vars: [string, string][] = [...plan.env, ['LINGARA_APP_SECRET', session.appSecret]]
  if (clientId !== undefined && session.clientSecret !== undefined) vars.push(['LINGARA_CLIENT_ID', clientId], ['LINGARA_CLIENT_SECRET', session.clientSecret])
  vars.push(['PORT', String(plan.port)], ['LINGARA_APP_PORT', String(plan.port)])
  return vars
}

/** `--show-env` (names only) and `--write-env` (mode 0600), both on stderr. */
function announce(ctx: Ctx, plan: Plan, session: Session, vars: [string, string][]): void {
  if (plan.showEnv) for (const [name] of vars) ctx.out.note(`env: ${name}`)
  if (plan.writeEnv === undefined) return
  writeFileSync(plan.writeEnv, vars.map(([k, v]) => `${k}=${v}\n`).join(''), { mode: 0o600 })
  ctx.out.note(message('apps_dev_env_written', { path: plan.writeEnv, expires_at: session.expiresAt }))
}

function startApp(plan: Plan, env: Record<string, string>): Group {
  const [shell, args] = process.platform === 'win32' ? ['cmd', ['/C', plan.start]] : ['sh', ['-c', plan.start]]
  const app = Group.spawn(shell, args as string[], { cwd: plan.dir, env, stdio: ['ignore', 'pipe', 'pipe'] })
  relay(app.child.stdout, APP_PREFIX)
  relay(app.child.stderr, APP_PREFIX)
  return app
}

/** The app's own exit: `ok` on status 0, else `app_exited`. */
export function appResult(plan: Plan, app: Group): void {
  if (app.status === 0) return
  const status = app.status === null || app.status === undefined ? 'a signal' : String(app.status)
  throw fromMessage(Exit.appExited, 'app_exited', { name: plan.name, status })
}

/** Step 4's wait: the port accepts within 60 s. `false` when the app already ended the session by exiting 0. */
async function waitApp(plan: Plan, run: Run): Promise<boolean> {
  const app = run.app as Group
  const listening = await waitListening(plan.port, LISTEN_WAIT_MS, () => run.stopped() || app.done)
  if (run.stopped()) throw interrupted()
  if (app.done) {
    appResult(plan, app)
    return false
  }
  if (!listening) throw fromMessage(Exit.appNotListening, 'app_not_listening', { name: plan.name, port: String(plan.port) })
  return true
}

async function live(ctx: Ctx, plan: Plan, session: Session, clientId: string | undefined, run: Run): Promise<void> {
  const vars = sessionVars(plan, session, clientId)
  announce(ctx, plan, session, vars)
  run.app = startApp(plan, childEnv(ctx.app.env.all(), vars))
  if (!(await waitApp(plan, run))) return
  await firstUrl(ctx, plan, session, run)
  await hold(ctx, plan, session, run)
}

async function stopChildren(run: Run): Promise<void> {
  await run.tunnel?.stop()
  await run.app?.stop(STOP_GRACE_MS)
}

export async function session(ctx: Ctx, plan: Plan): Promise<void> {
  const sig = signals()
  const clientId = plan.client ? str((await getObject(ctx, APP, plan.id)).client_id) || undefined : undefined
  const opened = await open(ctx, plan.id, plan.takeOver, plan.client).catch((e: unknown) => Promise.reject(held(e, plan.name)))
  const run: Run = { urls: [], lost: false, respawns: 0, stopped: sig.stopped }
  let error: unknown
  try {
    await live(ctx, plan, opened, clientId, run)
  } catch (e) {
    error = e instanceof Fail ? e : failure(e instanceof Error ? e.message : String(e))
  }
  if (!run.lost) await end(ctx, plan.id, opened.id).catch(() => undefined)
  await stopChildren(run)
  sig.release()
  if (ctx.out.json) {
    const exit = error instanceof Fail ? error.exit : Exit.ok
    ctx.out.jsonValue({ name: plan.name, app_id: plan.id, session_id: opened.id, urls: run.urls, exit: exitName(exit) })
  }
  if (error !== undefined) throw error
}
