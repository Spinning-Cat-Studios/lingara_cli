package apps

import (
	"strings"
	"time"

	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/assets"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/jsonv"
	"getlingara.com/cli/internal/supervise"
)

// Holding an apps dev session (ADR 16.10.26ae D7 steps 5–7): bring the
// tunnel up, point the dev version at it, then renew the lease every
// lease_secs / 3 until Ctrl+C, the app's exit or a lost session ends it. A
// cloudflared that dies is respawned up to five times, 1 s, 2 s, 4 s, 8 s
// and 16 s apart, and each new URL is pointed at; a sixth death, or a first
// incarnation that never comes up, is tunnel_failed.

var backoffSecs = []int{1, 2, 4, 8, 16}

const tick = 100 * time.Millisecond

func tunnelFailed(plan *devPlan, reason, tail string) *core.Fail {
	fail := core.Message(core.ExitTunnelFailed, "tunnel_failed", "name", plan.name, "reason", reason)
	if tail != "" {
		fail.Hints = append(fail.Hints, strings.Split(tail, "\n")...)
	}
	return fail
}

// point is step 6: PATCH …/dev {render_url, session_id, draft_version},
// then the ready line. A dev_session_held here means a take-over since the
// open.
func point(c *app.Ctx, plan *devPlan, s devSession, url string, r *run) *core.Fail {
	_, fail := devPatchWithRetry(c, plan.id, func(draftVersion any) any {
		return jsonv.Obj("render_url", url, "session_id", s.id, "draft_version", draftVersion)
	})
	if fail != nil && fail.Exit == core.ExitDevSessionHeld {
		r.lost = true
		return lost(plan.name)
	}
	if fail != nil {
		return fail
	}
	c.Out.Note(assets.Message("apps_dev_ready", "name", plan.name, "url", url))
	r.urls = append(r.urls, url)
	return nil
}

// incarnation is one cloudflared, up: the tunnel and its URL; else its last
// lines.
func incarnation(plan *devPlan) (*supervise.Tunnel, string, string) {
	t, fail := supervise.SpawnTunnel(plan.cloudflared, plan.port)
	if fail != nil {
		return nil, "", fail.Message
	}
	host := t.Hostname(core.Stopped)
	if host != "" && t.Ready(host, plan.probe, core.Stopped) {
		return t, "https://" + host + plan.renderPath, ""
	}
	tail := t.Tail()
	t.Stop()
	return nil, "", tail
}

// firstURL is steps 5 and 6 for the first incarnation, or the fixed
// --render-url.
func firstURL(c *app.Ctx, plan *devPlan, s devSession, r *run) *core.Fail {
	url := plan.fixedURL
	if plan.cloudflared != "" {
		t, up, tail := incarnation(plan)
		switch {
		case t == nil && core.Stopped():
			return core.Interrupted()
		case t == nil:
			return tunnelFailed(plan, "no URL, or no answer through it, within 30 s", tail)
		}
		r.tunnel, url = t, up
	}
	return point(c, plan, s, url, r)
}

// respawn is step 7's: until an incarnation is up and pointed at, or a
// sixth death.
func respawn(c *app.Ctx, plan *devPlan, s devSession, r *run) *core.Fail {
	tail := ""
	if r.tunnel != nil {
		tail = r.tunnel.Tail()
		r.tunnel.Stop()
		r.tunnel = nil
	}
	for {
		if r.respawns >= len(backoffSecs) {
			return tunnelFailed(plan, "cloudflared died a sixth time", tail)
		}
		if fail := core.SleepUnlessStopped(time.Duration(backoffSecs[r.respawns]) * time.Second); fail != nil {
			return fail
		}
		r.respawns++
		t, url, last := incarnation(plan)
		if t != nil {
			r.tunnel = t
			return point(c, plan, s, url, r)
		}
		if core.Stopped() {
			return core.Interrupted()
		}
		tail = last
	}
}

// renew is one scheduled renew. A refusal that means the session is over
// ends the run with no DELETE; anything else waits for the next scheduled
// renew, because a POST that was answered is never retried.
func renew(c *app.Ctx, plan *devPlan, s devSession, r *run) *core.Fail {
	if fail := devRenew(c, plan.id, s.id); fail != nil && fail.Exit == core.ExitDevSessionLost {
		r.lost = true
		return lost(plan.name)
	}
	return nil
}

// hold is step 7: the loop, until the first thing that ends the session.
func hold(c *app.Ctx, plan *devPlan, s devSession, r *run) *core.Fail {
	period := time.Duration(max(s.leaseSecs/3, 1)) * time.Second
	nextRenew := time.Now().Add(period)
	for {
		if core.Stopped() {
			return core.Interrupted()
		}
		if state, exited := r.app.Exited(); exited {
			return appResult(plan, state)
		}
		if r.tunnel != nil && !r.tunnel.Alive() {
			if fail := respawn(c, plan, s, r); fail != nil {
				return fail
			}
		}
		if !time.Now().Before(nextRenew) {
			if fail := renew(c, plan, s, r); fail != nil {
				return fail
			}
			nextRenew = time.Now().Add(period)
		}
		time.Sleep(tick)
	}
}
