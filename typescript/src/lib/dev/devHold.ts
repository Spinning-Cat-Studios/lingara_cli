// Holding an `apps dev` session (ADR 16.10.26ae D7 steps 5–7): bring the
// tunnel up, point the dev version at it, then renew the lease every
// `lease_secs / 3` until Ctrl+C, the app's exit or a lost session ends it. A
// cloudflared that dies is respawned up to five times, 1 s, 2 s, 4 s, 8 s and
// 16 s apart, and each new URL is pointed at; a sixth death, or a first
// incarnation that never comes up, is `tunnel_failed`.
import { sleep } from '../../supervise.js'
import { Tunnel } from '../../tunnel/cloudflared.js'
import { message } from '../assets.js'
import type { Ctx } from '../ctx.js'
import { Exit, Fail, fromMessage } from '../fail.js'
import { patchWithRetry, renew as renewLease, type Session } from './devApi.js'
import { appResult, interrupted, lost, type Run } from './devRun.js'
import type { Plan } from './plan.js'

const BACKOFF_SECS = [1, 2, 4, 8, 16]
const TICK_MS = 100

function tunnelFailed(plan: Plan, reason: string, tail: string): Fail {
  const fail = fromMessage(Exit.tunnelFailed, 'tunnel_failed', { name: plan.name, reason })
  for (const line of tail.split('\n').filter((l) => l !== '')) fail.hint(line)
  return fail
}

/** Step 6: `PATCH …/dev {render_url, session_id, draft_version}`, then the ready line. A `dev_session_held` here means a take-over since the open. */
async function point(ctx: Ctx, plan: Plan, session: Session, url: string, run: Run): Promise<void> {
  try {
    await patchWithRetry(ctx, plan.id, (draftVersion) => ({ render_url: url, session_id: session.id, draft_version: draftVersion }))
  } catch (e) {
    if (e instanceof Fail && e.exit === Exit.devSessionHeld) {
      run.lost = true
      throw lost(plan.name)
    }
    throw e
  }
  ctx.out.note(message('apps_dev_ready', { name: plan.name, url }))
  run.urls.push(url)
}

/** An incarnation that came up, at its URL, or one that did not, with its last lines. */
type Up = { url: string } | { tail: string }

/** One incarnation, up: its hostname and a probe that reached the app. */
async function incarnation(plan: Plan, binary: string, run: Run): Promise<Up> {
  const tunnel = await Tunnel.spawn(binary, plan.port)
  const host = await tunnel.hostname(run.stopped)
  if (host !== undefined && (await tunnel.ready(host, plan.probe, run.stopped))) {
    run.tunnel = tunnel
    return { url: `https://${host}${plan.renderPath}` }
  }
  const tail = tunnel.tail()
  await tunnel.stop()
  return { tail }
}

/** Steps 5 and 6 for the first incarnation, or the fixed `--render-url`. */
export async function firstUrl(ctx: Ctx, plan: Plan, session: Session, run: Run): Promise<void> {
  if ('fixed' in plan.target) return point(ctx, plan, session, plan.target.fixed, run)
  const up = await incarnation(plan, plan.target.cloudflared, run)
  if (run.stopped()) throw interrupted()
  if ('tail' in up) throw tunnelFailed(plan, 'no URL, or no answer through it, within 30 s', up.tail)
  return point(ctx, plan, session, up.url, run)
}

/** Sleeps `secs`, a tick at a time, unless the session is stopped. */
async function backoff(secs: number, run: Run): Promise<void> {
  const until = Date.now() + secs * 1000
  while (Date.now() < until) {
    if (run.stopped()) throw interrupted()
    await sleep(TICK_MS)
  }
}

/** Step 7's respawn: until an incarnation is up and pointed at, or a sixth death. */
async function respawn(ctx: Ctx, plan: Plan, session: Session, run: Run): Promise<void> {
  if (!('cloudflared' in plan.target)) return
  let tail = run.tunnel?.tail() ?? ''
  await run.tunnel?.stop()
  run.tunnel = undefined
  for (;;) {
    const secs = BACKOFF_SECS[run.respawns]
    if (secs === undefined) throw tunnelFailed(plan, 'cloudflared died a sixth time', tail)
    await backoff(secs, run)
    run.respawns += 1
    const up = await incarnation(plan, plan.target.cloudflared, run)
    if (run.stopped()) throw interrupted()
    if ('url' in up) return point(ctx, plan, session, up.url, run)
    tail = up.tail
  }
}

/** One scheduled renew. A refusal that means the session is over ends the run with no `DELETE`; anything else waits for the next scheduled renew. */
async function renew(ctx: Ctx, plan: Plan, session: Session, run: Run): Promise<void> {
  try {
    await renewLease(ctx, plan.id, session.id)
  } catch (e) {
    if (e instanceof Fail && e.exit === Exit.devSessionLost) {
      run.lost = true
      throw lost(plan.name)
    }
  }
}

/** Step 7: the loop, until the first thing that ends the session. */
export async function hold(ctx: Ctx, plan: Plan, session: Session, run: Run): Promise<void> {
  const periodMs = Math.max(1, Math.floor(session.leaseSecs / 3)) * 1000
  let nextRenew = Date.now() + periodMs
  for (;;) {
    if (run.stopped()) throw interrupted()
    if (run.app?.done === true) return appResult(plan, run.app)
    if (run.tunnel !== undefined && !run.tunnel.alive) await respawn(ctx, plan, session, run)
    if (Date.now() >= nextRenew) {
      await renew(ctx, plan, session, run)
      nextRenew = Date.now() + periodMs
    }
    await sleep(TICK_MS)
  }
}
