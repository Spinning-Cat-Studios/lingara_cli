package apps

import (
	"os"
	"strconv"
	"strings"
	"time"

	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/assets"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/jsonv"
	"getlingara.com/cli/internal/objects"
	"getlingara.com/cli/internal/supervise"
)

// An apps dev session (ADR 16.10.26ae D7 steps 3, 4 and 8): open it, start
// the app with the session's credentials, hand over to the hold loop, then
// end in order — DELETE the session, stop cloudflared, stop the app's group
// — each step running even if the one before it failed. Under --json,
// stdout carries one value, written here at exit. Ctrl+C and SIGTERM are
// claimed for the whole session, so they end it through this order and
// exit 130 (ADR 16.10.26af D9).

const (
	listenWait = 60 * time.Second
	stopGrace  = 5 * time.Second
	appPrefix  = "app │ "
)

// run is the children and what the session pointed at, for step 8 and the
// report.
type run struct {
	app    *supervise.Group
	tunnel *supervise.Tunnel
	urls   []string
	// lost is the session ending under us (taken over, expired): no DELETE.
	lost     bool
	respawns int
}

func (r *run) stopChildren() {
	if r.tunnel != nil {
		r.tunnel.Stop()
	}
	if r.app != nil {
		r.app.Stop(stopGrace)
	}
}

func lost(name string) *core.Fail {
	return core.Message(core.ExitDevSessionLost, "dev_session_lost", "name", name)
}

// held re-words 409 dev_session_held with the app's name and the lease.
func held(fail *core.Fail, name string) *core.Fail {
	if fail.Exit != core.ExitDevSessionHeld {
		return fail
	}
	body, _ := jsonv.Parse([]byte(fail.Body))
	lease := jsonv.TextOr(body, "its lease ends", "lease_expires_at")
	return core.Message(core.ExitDevSessionHeld, "dev_session_held", "name", name, "lease_expires_at", lease)
}

func session(c *app.Ctx, plan *devPlan) *core.Fail {
	release := core.Claim()
	defer release()
	clientID := ""
	if plan.client {
		object, fail := objects.Get(c, objects.App, plan.id)
		if fail != nil {
			return fail
		}
		clientID = jsonv.Text(object, "client_id")
	}
	s, fail := devOpen(c, plan.id, plan.takeOver, plan.client)
	if fail != nil {
		return held(fail, plan.name)
	}
	r := &run{}
	result := live(c, plan, s, clientID, r)
	if !r.lost {
		devEnd(c, plan.id, s.id)
	}
	r.stopChildren()
	if c.Out.JSON {
		exit := core.ExitOk
		if result != nil {
			exit = result.Exit
		}
		urls := append([]string{}, r.urls...)
		c.Out.JSONValue(jsonv.Obj("name", plan.name, "app_id", plan.id, "session_id", s.id, "urls", urls, "exit", exit.Name()))
	}
	return result
}

func live(c *app.Ctx, plan *devPlan, s devSession, clientID string, r *run) *core.Fail {
	extra := sessionVars(plan, s, clientID)
	if fail := announce(c, plan, s, extra); fail != nil {
		return fail
	}
	group, fail := startApp(plan, supervise.ChildEnv(c.App.Env, extra))
	if fail != nil {
		return fail
	}
	r.app = group
	listening, fail := waitApp(plan, r)
	if fail != nil || !listening {
		return fail
	}
	if fail := firstURL(c, plan, s, r); fail != nil {
		return fail
	}
	return hold(c, plan, s, r)
}

// sessionVars is what the app's environment gains (D5, D7 step 4): the
// row's env and env_file, the session's credentials, and the port.
func sessionVars(plan *devPlan, s devSession, clientID string) [][2]string {
	vars := append([][2]string(nil), plan.env...)
	vars = append(vars, [2]string{"LINGARA_APP_SECRET", s.appSecret})
	if clientID != "" && s.hasClient {
		vars = append(vars, [2]string{"LINGARA_CLIENT_ID", clientID}, [2]string{"LINGARA_CLIENT_SECRET", s.clientSecret})
	}
	port := strconv.Itoa(plan.port)
	return append(vars, [2]string{"PORT", port}, [2]string{"LINGARA_APP_PORT", port})
}

// announce is --show-env (names only) and --write-env (mode 0600), both on
// stderr.
func announce(c *app.Ctx, plan *devPlan, s devSession, vars [][2]string) *core.Fail {
	if plan.showEnv {
		for _, kv := range vars {
			c.Out.Note("env: " + kv[0])
		}
	}
	if plan.writeEnv == "" {
		return nil
	}
	var text strings.Builder
	for _, kv := range vars {
		text.WriteString(kv[0] + "=" + kv[1] + "\n")
	}
	if err := os.WriteFile(plan.writeEnv, []byte(text.String()), 0o600); err != nil {
		return core.Failure(plan.writeEnv + ": " + err.Error())
	}
	if err := os.Chmod(plan.writeEnv, 0o600); err != nil {
		return core.Failure(plan.writeEnv + ": " + err.Error())
	}
	c.Out.Note(assets.Message("apps_dev_env_written", "path", plan.writeEnv, "expires_at", s.expiresAt))
	return nil
}

func startApp(plan *devPlan, env []string) (*supervise.Group, *core.Fail) {
	cmd := supervise.Shell(plan.start)
	cmd.Dir, cmd.Env = plan.dir, env
	group, output, err := supervise.SpawnCapturing(cmd, true)
	if err != nil {
		return nil, core.Failure(plan.start + ": " + err.Error())
	}
	supervise.Relay(output, appPrefix)
	return group, nil
}

// appResult is the app's own exit: ok on status 0, else app_exited.
func appResult(plan *devPlan, state *os.ProcessState) *core.Fail {
	if state != nil && state.Success() {
		return nil
	}
	return core.Message(core.ExitAppExited, "app_exited", "name", plan.name, "status", supervise.Status(state))
}

// waitApp is step 4's wait: the port accepts within 60 s. false when the
// app already ended the session by exiting 0.
func waitApp(plan *devPlan, r *run) (bool, *core.Fail) {
	listening := supervise.WaitListening(plan.port, listenWait, func() bool {
		_, exited := r.app.Exited()
		return core.Stopped() || exited
	})
	if core.Stopped() {
		return false, core.Interrupted()
	}
	if state, exited := r.app.Exited(); exited && !listening {
		return false, appResult(plan, state)
	}
	if !listening {
		return false, core.Message(core.ExitAppNotListening, "app_not_listening", "name", plan.name, "port", strconv.Itoa(plan.port))
	}
	return true, nil
}
