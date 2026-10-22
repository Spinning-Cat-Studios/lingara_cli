package com.getlingara.cli.java.commands.apps;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.core.Assets;
import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Interrupts;
import com.getlingara.cli.java.core.Json;
import com.getlingara.cli.java.supervise.Shell;
import com.getlingara.cli.java.supervise.Tunnel;

/**
 * Holding an {@code apps dev} session (ADR 16.10.26ae D7 steps 5–7): bring the tunnel up, point
 * the dev version at it, then renew the lease every {@code lease_secs / 3} until Ctrl+C, the
 * app's exit or a lost session ends it. A cloudflared that dies is respawned up to five times, 1
 * s, 2 s, 4 s, 8 s and 16 s apart, and each new URL is pointed at; a sixth death, or a first
 * incarnation that never comes up, is {@code tunnel_failed}.
 */
final class DevHold {
    private static final long[] BACKOFF_SECS = {1, 2, 4, 8, 16};
    private static final long TICK_MS = 100;

    private DevHold() {}

    /** One incarnation that came up: the tunnel and the URL it serves the render path at. */
    private record Up(Tunnel tunnel, String url) {}

    private static Fail tunnelFailed(Dev.Plan plan, String reason, String tail) {
        Fail fail = Fail.message(Exit.TUNNEL_FAILED, "tunnel_failed", "name", plan.name(), "reason", reason);
        tail.lines().forEach(fail::hint);
        return fail;
    }

    /** Step 6: {@code PATCH …/dev {render_url, session_id, draft_version}}, then the ready line. */
    private static void point(Ctx ctx, Dev.Plan plan, DevApi.Session session, String url, DevState run) {
        try {
            DevApi.patchWithRetry(ctx, plan.id(), draft -> {
                ObjectNode body = Json.object();
                body.put("render_url", url);
                body.put("session_id", session.id());
                body.set("draft_version", draft);
                return body;
            });
        } catch (Fail fail) {
            // A dev_session_held here means a take-over since the open.
            if (fail.exit() == Exit.DEV_SESSION_HELD) {
                run.lost = true;
                throw DevRun.lost(plan.name());
            }
            throw fail;
        }
        ctx.out.note(Assets.message("apps_dev_ready", "name", plan.name(), "url", url));
        run.urls.add(url);
    }

    /** One incarnation, up; else {@code null} with its last lines in {@code tail[0]}. */
    private static Up incarnation(Dev.Plan plan, String[] tail) {
        Tunnel tunnel = Tunnel.spawn(plan.cloudflared(), plan.port());
        String host = tunnel.hostname(Interrupts::raised);
        if (host != null && tunnel.ready(host, plan.probe(), Interrupts::raised)) {
            return new Up(tunnel, "https://" + host + plan.renderPath());
        }
        tail[0] = tunnel.tail();
        tunnel.stop();
        return null;
    }

    /** Steps 5 and 6 for the first incarnation, or the fixed {@code --render-url}. */
    static void firstUrl(Ctx ctx, Dev.Plan plan, DevApi.Session session, DevState run) {
        String url = plan.fixedUrl();
        if (plan.cloudflared() != null) {
            String[] tail = {""};
            Up up = incarnation(plan, tail);
            if (up == null && Interrupts.raised()) {
                throw DevRun.interrupted();
            }
            if (up == null) {
                throw tunnelFailed(plan, "no URL, or no answer through it, within 30 s", tail[0]);
            }
            run.tunnel = up.tunnel();
            url = up.url();
        }
        point(ctx, plan, session, url, run);
    }

    /** Sleeps {@code secs}, a tick at a time, unless the session is stopped. */
    private static void backoff(long secs) {
        long until = System.currentTimeMillis() + secs * 1000;
        while (System.currentTimeMillis() < until) {
            if (Interrupts.raised()) {
                throw DevRun.interrupted();
            }
            Shell.sleep(TICK_MS);
        }
    }

    /** Step 7's respawn: until an incarnation is up and pointed at, or a sixth death. */
    private static void respawn(Ctx ctx, Dev.Plan plan, DevApi.Session session, DevState run) {
        String[] tail = {""};
        if (run.tunnel != null) {
            tail[0] = run.tunnel.tail();
            run.tunnel.stop();
            run.tunnel = null;
        }
        while (true) {
            if (run.respawns >= BACKOFF_SECS.length) {
                throw tunnelFailed(plan, "cloudflared died a sixth time", tail[0]);
            }
            backoff(BACKOFF_SECS[run.respawns]);
            run.respawns++;
            Up up = incarnation(plan, tail);
            if (up != null) {
                run.tunnel = up.tunnel();
                point(ctx, plan, session, up.url(), run);
                return;
            }
            if (Interrupts.raised()) {
                throw DevRun.interrupted();
            }
        }
    }

    /**
     * One scheduled renew. A refusal that means the session is over ends the run with no {@code
     * DELETE}; anything else waits for the next scheduled renew, because a {@code POST} that was
     * answered is never retried.
     */
    private static void renew(Ctx ctx, Dev.Plan plan, DevApi.Session session, DevState run) {
        try {
            DevApi.renew(ctx, plan.id(), session.id());
        } catch (Fail fail) {
            if (fail.exit() == Exit.DEV_SESSION_LOST) {
                run.lost = true;
                throw DevRun.lost(plan.name());
            }
        }
    }

    /** Step 7: the loop, until the first thing that ends the session. */
    static void hold(Ctx ctx, Dev.Plan plan, DevApi.Session session, DevState run) {
        long periodMs = Math.max(1, session.leaseSecs() / 3) * 1000;
        long nextRenew = System.currentTimeMillis() + periodMs;
        while (true) {
            if (Interrupts.raised()) {
                throw DevRun.interrupted();
            }
            Integer status = run.app == null ? null : run.app.exited();
            if (status != null) {
                DevRun.appResult(plan, status);
                return;
            }
            if (run.tunnel != null && !run.tunnel.alive()) {
                respawn(ctx, plan, session, run);
            }
            if (System.currentTimeMillis() >= nextRenew) {
                renew(ctx, plan, session, run);
                nextRenew = System.currentTimeMillis() + periodMs;
            }
            Shell.sleep(TICK_MS);
        }
    }
}
