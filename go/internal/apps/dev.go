package apps

import (
	"bufio"
	"os"
	"strings"

	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/assets"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/jsonv"
	"getlingara.com/cli/internal/platform"
	"getlingara.com/cli/internal/supervise"
)

// lingara apps dev [name] (ADR 16.10.26ae D7), steps 1 and 2: resolve the
// row and run every refusal offline, so a refused run sends no request;
// then register a row with no id (D6 step 4) and hand over to the session.
// --reset is here too: one PATCH {render_url: null} and nothing else.

// DevArgs is apps dev's argument and flags.
type DevArgs struct {
	Name      string
	Port      int
	Tunnel    string
	RenderURL *string
	TakeOver  bool
	WriteEnv  string
	ShowEnv   bool
	Reset     bool
	Config    string
}

// devPlan is everything a session needs, decided before the first request.
// cloudflared is the binary to spawn, or "" with fixedURL for --tunnel
// none.
type devPlan struct {
	name, id, dir, start, renderPath string
	port                             int
	cloudflared, fixedURL            string
	takeOver, client, showEnv        bool
	env                              [][2]string
	writeEnv                         string
	// probe is LINGARA_TUNNEL_PROBE_URL: where readiness is probed, never
	// what is pointed at.
	probe string
}

// Dev runs apps dev.
func Dev(c *app.Ctx, a DevArgs) *core.Fail {
	t, cwd, fail := Open(c, a.Config)
	if fail != nil {
		return fail
	}
	row, fail := Resolve(t, a.Name, cwd)
	if fail != nil {
		return fail
	}
	if a.Reset {
		return reset(c, row)
	}
	plan, fail := preflight(c, a, t, row, cwd)
	if fail != nil {
		return fail
	}
	plan.id = row.ID
	if plan.id == "" {
		m, fail := ReadManifest(t, row, cwd)
		if fail != nil {
			return fail
		}
		registered, fail := RegisterRow(c, t, row, m)
		if fail != nil {
			return fail
		}
		// The registration printed the app; apps dev prints one value, at
		// its exit.
		c.Out.Stdout.Reset()
		plan.id = jsonv.Text(registered, "id")
	}
	return session(c, plan)
}

func ignoredOrRefuse(path, cwd string) *core.Fail {
	if platform.Ignored(path) {
		return nil
	}
	return core.Message(core.ExitUsage, "path_not_ignored", "path", platform.Relative(path, cwd))
}

func target(c *app.Ctx, a DevArgs, plan *devPlan) *core.Fail {
	switch {
	case a.Tunnel == "none" && a.RenderURL != nil:
		plan.fixedURL = *a.RenderURL
		return nil
	case a.Tunnel == "none":
		return core.Usage("--tunnel none needs --render-url")
	case a.RenderURL != nil:
		return core.Usage("--render-url needs --tunnel none")
	}
	binary, fail := supervise.FindCloudflared(c.App.Env)
	plan.cloudflared = binary
	return fail
}

// envFile is KEY=VALUE lines; blank lines, # comments and an export prefix
// are skipped, and one pair of matching quotes is dropped.
func envFile(path string) ([][2]string, *core.Fail) {
	f, err := os.Open(path)
	if err != nil {
		return nil, core.Usage(path + ": " + err.Error())
	}
	defer func() { _ = f.Close() }()
	var out [][2]string
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := strings.TrimPrefix(strings.TrimSpace(scanner.Text()), "export ")
		k, v, ok := strings.Cut(line, "=")
		if !ok || strings.HasPrefix(line, "#") || strings.TrimSpace(k) == "" {
			continue
		}
		out = append(out, [2]string{strings.TrimSpace(k), unquote(strings.TrimSpace(v))})
	}
	return out, nil
}

func unquote(v string) string {
	for _, q := range []string{`"`, `'`} {
		if len(v) >= 2 && strings.HasPrefix(v, q) && strings.HasSuffix(v, q) {
			return v[1 : len(v)-1]
		}
	}
	return v
}

// preflight is step 2: every refusal before the network. The session
// record is read, never refreshed; a refresh, if due, rides the first
// request.
func preflight(c *app.Ctx, a DevArgs, t *platform.Toml, row platform.Row, cwd string) (*devPlan, *core.Fail) {
	if row.Start == "" {
		return nil, core.Usage(row.Name + "'s row has no start command")
	}
	plan := &devPlan{name: row.Name, dir: t.ProjectDir(row), port: row.Port, start: row.Start, renderPath: row.RenderPath,
		takeOver: a.TakeOver, showEnv: a.ShowEnv, env: append([][2]string(nil), row.Env...)}
	if fail := envAndFiles(plan, a, row, cwd); fail != nil {
		return nil, fail
	}
	if fail := target(c, a, plan); fail != nil {
		return nil, fail
	}
	if fail := requireSignIn(c); fail != nil {
		return nil, fail
	}
	if a.Port != 0 {
		plan.port = a.Port
	}
	if plan.renderPath == "" {
		plan.renderPath = "/"
	}
	for _, cred := range row.Credentials {
		plan.client = plan.client || cred == "client_credentials"
	}
	plan.probe, _ = c.App.Env.Get("LINGARA_TUNNEL_PROBE_URL")
	return plan, nil
}

// requireSignIn refuses a run with neither CI credentials nor a stored
// profile, offline.
func requireSignIn(c *app.Ctx) *core.Fail {
	if _, _, ci := c.CIPair(); ci {
		return nil
	}
	record, fail := c.App.Stores.Load(c.Profile)
	if fail == nil && record == nil {
		return core.Auth("not signed in: run `lingara login`")
	}
	return fail
}

// envAndFiles reads the row's env_file and checks both files that would
// hold secrets are gitignored.
func envAndFiles(plan *devPlan, a DevArgs, row platform.Row, cwd string) *core.Fail {
	if row.EnvFile != "" {
		path := join(plan.dir, row.EnvFile)
		if fail := ignoredOrRefuse(path, cwd); fail != nil {
			return fail
		}
		pairs, fail := envFile(path)
		if fail != nil {
			return fail
		}
		plan.env = append(plan.env, pairs...)
	}
	if a.WriteEnv != "" {
		plan.writeEnv = join(cwd, a.WriteEnv)
		return ignoredOrRefuse(plan.writeEnv, cwd)
	}
	return nil
}

// reset is --reset: the owner's install back on the serving version. A live
// session refuses it (apps_dev_reset_held).
func reset(c *app.Ctx, row platform.Row) *core.Fail {
	if row.ID == "" {
		return core.Usage(row.Name + " is not registered yet: run lingara apps update " + row.Name + " first")
	}
	_, fail := devPatchWithRetry(c, row.ID, func(draftVersion any) any {
		return jsonv.Obj("render_url", nil, "draft_version", draftVersion)
	})
	if fail != nil && fail.Exit == core.ExitDevSessionHeld {
		return core.Message(core.ExitDevSessionHeld, "apps_dev_reset_held", "name", row.Name)
	}
	if fail != nil {
		return fail
	}
	c.Out.Note(assets.Message("apps_dev_reset", "name", row.Name))
	c.Out.Nothing()
	return nil
}
