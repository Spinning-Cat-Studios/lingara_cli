// Holding an `apps dev` session (ADR 16.10.26ae D7 steps 5–7): bring the
// tunnel up, point the dev version at it, then renew the lease every
// `lease_secs / 3` until Ctrl+C, the app's exit or a lost session ends it. A
// cloudflared that dies is respawned up to five times, 1 s, 2 s, 4 s, 8 s and
// 16 s apart, and each new URL is pointed at; a sixth death, or a first
// incarnation that never comes up, is `tunnel_failed`.
package com.getlingara.cli.kotlin.apps

import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.core.Assets
import com.getlingara.cli.kotlin.core.Exit
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Json
import com.getlingara.cli.kotlin.core.Signals
import com.getlingara.cli.kotlin.supervise.Tunnel
import java.nio.file.Path

private val BACKOFF_SECS = listOf(1L, 2L, 4L, 8L, 16L)
private const val TICK_MILLIS = 100L

object DevHold {
    private fun tunnelFailed(plan: DevPlan, reason: String, tail: String): Fail {
        val fail = Fail.message(Exit.TUNNEL_FAILED, "tunnel_failed", "name" to plan.name, "reason" to reason)
        tail.lines().filter { it.isNotEmpty() }.forEach { fail.hint(it) }
        return fail
    }

    /** Step 6: `PATCH …/dev {render_url, session_id, draft_version}`, then the ready line. A `dev_session_held` here means a take-over since the open. */
    private fun point(ctx: Ctx, plan: DevPlan, session: DevSession, url: String, run: Run) {
        try {
            DevApi.patchWithRetry(ctx, plan.id) { dv -> Json.obj("render_url" to url, "session_id" to session.id, "draft_version" to dv) }
        } catch (fail: Fail) {
            if (fail.exit != Exit.DEV_SESSION_HELD) throw fail
            run.lost = true
            throw DevRun.lost(plan.name)
        }
        ctx.out.note(Assets.message("apps_dev_ready", "name" to plan.name, "url" to url))
        run.urls.add(url)
    }

    /** One incarnation, up: the tunnel and its URL; else its last lines. */
    private fun incarnation(plan: DevPlan, binary: Path): Result<Pair<Tunnel, String>> {
        val tunnel = try {
            Tunnel.spawn(binary, plan.port)
        } catch (fail: Fail) {
            return Result.failure(IllegalStateException(fail.text))
        }
        val host = tunnel.hostname(Signals::stopped)?.takeIf { tunnel.ready(it, plan.probe, Signals::stopped) }
        if (host != null) return Result.success(tunnel to "https://$host${plan.renderPath}")
        val tail = tunnel.tail()
        tunnel.stop()
        return Result.failure(IllegalStateException(tail))
    }

    /** Steps 5 and 6 for the first incarnation, or the fixed `--render-url`. */
    fun firstUrl(ctx: Ctx, plan: DevPlan, session: DevSession, run: Run) {
        val binary = plan.target.binary ?: return point(ctx, plan, session, plan.target.url.orEmpty(), run)
        val up = incarnation(plan, binary)
        Signals.check()
        val (tunnel, url) = up.getOrElse { throw tunnelFailed(plan, "no URL, or no answer through it, within 30 s", it.message.orEmpty()) }
        run.tunnel = tunnel
        point(ctx, plan, session, url, run)
    }

    /** Step 7's respawn: until an incarnation is up and pointed at, or a sixth death. */
    private fun respawn(ctx: Ctx, plan: DevPlan, session: DevSession, run: Run) {
        val binary = plan.target.binary ?: return
        var tail = run.tunnel?.let { dead -> dead.tail().also { dead.stop() } }.orEmpty()
        run.tunnel = null
        while (true) {
            val secs = BACKOFF_SECS.getOrNull(run.respawns) ?: throw tunnelFailed(plan, "cloudflared died a sixth time", tail)
            Signals.sleep(secs * 1000)
            run.respawns++
            val up = incarnation(plan, binary)
            Signals.check()
            val (tunnel, url) = up.getOrNull() ?: run {
                tail = up.exceptionOrNull()?.message.orEmpty()
                null
            } ?: continue
            run.tunnel = tunnel
            return point(ctx, plan, session, url, run)
        }
    }

    /** One scheduled renew. A refusal that means the session is over ends the run with no `DELETE`; anything else waits for the next renew, because a `POST` that was answered is never retried. */
    private fun renew(ctx: Ctx, plan: DevPlan, session: DevSession, run: Run) {
        try {
            DevApi.renew(ctx, plan.id, session.id)
        } catch (fail: Fail) {
            if (fail.exit != Exit.DEV_SESSION_LOST) return
            run.lost = true
            throw DevRun.lost(plan.name)
        }
    }

    /** Step 7: the loop, until the first thing that ends the session. */
    fun hold(ctx: Ctx, plan: DevPlan, session: DevSession, run: Run) {
        val period = maxOf(session.leaseSecs / 3, 1L) * 1000
        var nextRenew = System.currentTimeMillis() + period
        while (true) {
            Signals.check()
            run.app?.exited()?.let { return DevRun.appResult(plan, it) }
            if (run.tunnel?.alive() == false) respawn(ctx, plan, session, run)
            if (System.currentTimeMillis() >= nextRenew) {
                renew(ctx, plan, session, run)
                nextRenew = System.currentTimeMillis() + period
            }
            Thread.sleep(TICK_MILLIS)
        }
    }
}
