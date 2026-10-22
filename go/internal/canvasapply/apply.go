// Package canvasapply is canvas apply, matryoshka-style (ADR 16.10.26ad
// D7; contract B7). A draft creates nothing; apply plans in the web
// planner's order (core/canvas), dry-runs the plan, then runs this CLI's
// own verbs in-process, one per op, records each object's runtime_id and
// activates. --dry-run prints the verdicts and --print-commands the argvs;
// neither writes.
package canvasapply

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"

	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/assets"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/core/canvas"
	"getlingara.com/cli/internal/httpx"
	"getlingara.com/cli/internal/jsonv"
	"getlingara.com/cli/internal/objects"
)

var verdictColumns = objects.Columns("HANDLE", "handle", "KIND", "kind", "OP", "op", "VERDICT", "verdict", "CODE", "code")

// Planned is what one apply works over: the draft, its record and the live
// lists.
type Planned struct {
	CanvasID     string
	DraftVersion int64
	Kept         canvas.Records
	Live         canvas.Live
	Ops          []canvas.Op
}

func refused(message string) *core.Fail {
	return core.NewFail(core.ExitRefused, "canvas_apply_refused", message)
}

// recorded is the active record overlaid by the draft's, kept to the
// draft's nodes (recordedObjects).
func recorded(c any, def canvas.Definition) canvas.Records {
	all := canvas.Records{}
	for _, side := range []string{"active", "draft"} {
		objs := jsonv.AsObject(jsonv.Get(c, side, "objects"))
		for _, handle := range objs.Keys() {
			all[handle] = jsonv.Text(objs.Get(handle), "id")
		}
	}
	for handle := range all {
		if !def.HasNode(handle) {
			delete(all, handle)
		}
	}
	return all
}

func rows(c *app.Ctx, k objects.Kind) ([]canvas.Row, *core.Fail) {
	items, fail := objects.List(c, k)
	out := make([]canvas.Row, len(items))
	for i, item := range items {
		out[i] = canvas.ReadRow(item)
	}
	return out, fail
}

// read is D7 step 1: the canvas and the three lists, every page.
func read(c *app.Ctx, id string) (any, canvas.Live, *core.Fail) {
	var live canvas.Live
	cv, fail := objects.CanvasGet(c, id)
	if fail != nil {
		return nil, live, fail
	}
	if jsonv.Get(cv, "draft") == nil {
		return nil, live, refused("nothing to apply: save a draft with `lingara canvas draft`")
	}
	if live.Clients, fail = rows(c, objects.OAuthClient); fail != nil {
		return nil, live, fail
	}
	if live.Webhooks, fail = rows(c, objects.WebhookOutbound); fail != nil {
		return nil, live, fail
	}
	live.Apps, fail = rows(c, objects.App)
	return cv, live, fail
}

func refusalFail(r *canvas.Refusal) *core.Fail {
	switch r.Kind {
	case "ephemeral":
		return core.Message(core.ExitRenderURLEphemeral, "render_url_ephemeral", "file", "node "+r.Handle+"'s manifest")
	case "script":
		return refused("node " + r.Handle + " runs a script; apply this canvas in the browser")
	case "fields":
		return refused("node " + r.Handle + "'s fields are not one the builder can apply: " + r.Detail)
	case "no_client":
		return refused("node " + r.Handle + " needs exactly one client node upstream")
	}
	return refused("the canvas's edges form a cycle")
}

// Plan is steps 1 and 2.
func Plan(c *app.Ctx, id string) (*Planned, *core.Fail) {
	cv, live, fail := read(c, id)
	if fail != nil {
		return nil, fail
	}
	def := canvas.ReadDefinition(jsonv.Get(cv, "draft", "definition"))
	kept := canvas.StillLive(def, recorded(cv, def), live)
	ops, refusal := canvas.Build(def, kept)
	if refusal != nil {
		return nil, refusalFail(refusal)
	}
	draftVersion, _ := jsonv.Int(jsonv.Get(cv, "draft", "draft_version"))
	return &Planned{CanvasID: id, DraftVersion: draftVersion, Kept: kept, Live: live, Ops: ops}, nil
}

// dryRun is D7 step 3: zero writes, canvas:read.
func dryRun(c *app.Ctx, ops []canvas.Op) (any, *core.Fail) {
	wire := make([]any, len(ops))
	for i, op := range ops {
		wire[i] = op.Wire()
	}
	return c.Call(httpx.Request{Route: httpx.Fixed("canvas.dry_run"), JSON: jsonv.Obj("ops", wire)})
}

func stopFail(stop *canvas.Stop) *core.Fail {
	if stop.Replace != "" {
		return refused("node " + stop.Replace + ": unexpected `would_replace` from the server; nothing was applied")
	}
	fail := refused(fmt.Sprintf("the dry-run refused %d op(s); nothing was applied", len(stop.Refused)))
	for _, r := range stop.Refused {
		fail.Hints = append(fail.Hints, r[0]+": "+r[2]+" ("+r[1]+")")
	}
	return fail
}

// Options is apply's two stopping flags.
type Options struct {
	DryRun, PrintCommands bool
}

// Run is canvas apply <id>.
func Run(c *app.Ctx, id string, opts Options) *core.Fail {
	planned, fail := Plan(c, id)
	if fail != nil {
		return fail
	}
	answer, fail := dryRun(c, planned.Ops)
	if fail != nil {
		return fail
	}
	verdicts := jsonv.Arr(jsonv.Get(answer, "verdicts"))
	if opts.DryRun {
		return printVerdicts(c, answer, verdicts)
	}
	steps, stop := canvas.Steps(planned.Ops, verdicts, planned.Live, planned.Kept)
	if stop != nil {
		return stopFail(stop)
	}
	manifests, fail := writeManifests(planned.Ops)
	if fail != nil {
		return fail
	}
	if opts.PrintCommands {
		printCommands(c, steps, manifests)
		return nil
	}
	defer func() {
		for _, path := range manifests {
			_ = os.Remove(path)
		}
	}()
	return execute(c, planned, steps, manifests)
}

func printVerdicts(c *app.Ctx, answer any, verdicts []any) *core.Fail {
	if c.Out.JSON {
		c.Out.JSONValue(answer)
	} else {
		c.Out.List(verdicts, verdictColumns)
	}
	if ok, isBool := jsonv.Bool(jsonv.Get(answer, "ok")); isBool && !ok {
		return refused("the dry-run refused at least one op")
	}
	return nil
}

// writeManifests writes each app op's manifest as the file its --manifest
// names.
func writeManifests(ops []canvas.Op) (map[string]string, *core.Fail) {
	out := map[string]string{}
	for _, op := range ops {
		if op.Kind != "app" {
			continue
		}
		name := "lingara-" + strconv.Itoa(os.Getpid()) + "-" + op.Handle + ".manifest.json"
		path := filepath.Join(os.TempDir(), name)
		if err := os.WriteFile(path, []byte(jsonv.Marshal(jsonv.Get(op.Body, "manifest"))), 0o600); err != nil {
			return nil, core.Failure(path + ": " + err.Error())
		}
		out[op.Handle] = path
	}
	return out, nil
}

// printCommands makes the matryoshka visible: what apply would run, as
// shell lines.
func printCommands(c *app.Ctx, steps []canvas.Step, manifests map[string]string) {
	lines := []string{}
	for _, s := range steps {
		if len(s.Argv) > 0 {
			lines = append(lines, canvas.ShellLine(s.Argv, func(h string) string { return manifests[h] }))
		}
	}
	if c.Out.JSON {
		c.Out.JSONValue(lines)
		return
	}
	for _, line := range lines {
		c.Out.Print(line + "\n")
	}
}

func partialHint(planned *Planned, done, total int) string {
	return assets.Message("canvas_apply_partial", "done", strconv.Itoa(done), "total", strconv.Itoa(total), "id", planned.CanvasID)
}
