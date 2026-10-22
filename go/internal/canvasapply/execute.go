package canvasapply

import (
	"io"
	"sort"

	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/core/canvas"
	"getlingara.com/cli/internal/httpx"
	"getlingara.com/cli/internal/jsonv"
	"getlingara.com/cli/internal/objects"
)

// D7 steps 4–7. Each step's argv is dispatched in-process through
// App.Dispatch, main's own entry point, with --json, so no credential
// crosses a process boundary and no second token exchange happens. Apply
// records each object's runtime_id (a secret's id), never its platform
// object id, then activates complete, or partial at the first failure.

// publishPolls: a runtime_id still null is re-read once a second, ten times
// (D7).
const publishPolls = 10

type state struct {
	recorded canvas.Records
	// made is handle → the platform object id an earlier step made.
	made map[string]string
	// minted is handle → the secret its client's create minted.
	minted  map[string]string
	secrets []*jsonv.Object
	rows    [][]string
}

func kindOf(nodeKind string) objects.Kind {
	switch nodeKind {
	case "client":
		return objects.OAuthClient
	case "webhook":
		return objects.WebhookOutbound
	}
	return objects.App
}

func execute(c *app.Ctx, planned *Planned, steps []canvas.Step, manifests map[string]string) *core.Fail {
	s := &state{recorded: canvas.Records{}, made: map[string]string{}, minted: map[string]string{}}
	for h, id := range planned.Kept {
		s.recorded[h] = id
	}
	for done, step := range steps {
		if fail := s.step(c, step, manifests); fail != nil {
			return s.partial(c, planned, done, len(steps), fail)
		}
	}
	if _, fail := activate(c, planned, s.recorded, "complete"); fail != nil {
		return fail
	}
	s.report(c, "complete")
	return nil
}

func (s *state) step(c *app.Ctx, step canvas.Step, manifests map[string]string) *core.Fail {
	switch {
	case step.Records.Kind == "client_secret":
		id, ok := s.minted[step.Records.Of]
		if !ok {
			return core.Failure(step.Records.Of + " minted no secret")
		}
		for _, secret := range s.secrets {
			if secret.Get("handle") == step.Records.Of {
				secret.Set("handle", step.Handle)
			}
		}
		s.recorded[step.Handle] = id
	case len(step.Argv) > 0:
		argv, fail := s.resolve(step.Argv, manifests)
		if fail != nil {
			return fail
		}
		answer, fail := s.invoke(c, step.Handle, argv)
		if fail != nil {
			return fail
		}
		if fail := s.record(c, step, answer); fail != nil {
			return fail
		}
	}
	id, ok := s.recorded[step.Handle]
	if !ok {
		id = "-"
	}
	s.rows = append(s.rows, []string{step.Handle, step.Kind, step.Action, id})
	return nil
}

func (s *state) resolve(argv []canvas.Part, manifests map[string]string) ([]string, *core.Fail) {
	out := make([]string, len(argv))
	for i, p := range argv {
		var ok bool
		switch p.Kind {
		case canvas.PartMade:
			if out[i], ok = s.made[p.Value]; !ok {
				return nil, core.Failure(p.Value + " was not made")
			}
		case canvas.PartManifest:
			if out[i], ok = manifests[p.Value]; !ok {
				return nil, core.Failure("no manifest for " + p.Value)
			}
		default:
			out[i] = p.Value
		}
	}
	return out, nil
}

// invoke runs one inner verb; its JSON, any secret it revealed kept even
// when it failed part-way.
func (s *state) invoke(c *app.Ctx, handle string, argv []string) (any, *core.Fail) {
	argv = append(argv, "--json", "--profile", c.Profile)
	outcome := c.App.Dispatch(argv, io.Discard)
	answer, _ := jsonv.Parse(outcome.Stdout)
	var secret *jsonv.Object
	if inner := jsonv.AsObject(jsonv.Get(answer, "secret")); inner != nil {
		secret = jsonv.Obj("handle", handle, "id", inner.Get("id"), "value", inner.Get("value"))
	} else if id, ok := jsonv.Str(jsonv.Get(answer, "secret_id")); ok {
		secret = jsonv.Obj("handle", handle, "id", id, "value", jsonv.Get(answer, "secret"))
	}
	if secret != nil {
		s.minted[handle] = jsonv.Text(secret, "id")
		s.secrets = append(s.secrets, secret)
	}
	if outcome.Fail != nil {
		return nil, outcome.Fail
	}
	return answer, nil
}

func (s *state) record(c *app.Ctx, step canvas.Step, answer any) *core.Fail {
	switch step.Records.Kind {
	case "runtime_id":
		platformID := jsonv.Text(answer, "id")
		s.made[step.Handle] = platformID
		runtimeID, fail := runtimeID(c, kindOf(step.Kind), platformID, answer)
		if fail != nil {
			return fail
		}
		s.recorded[step.Handle] = runtimeID
	case "secret_id":
		s.recorded[step.Handle] = jsonv.Text(answer, "secret_id")
	}
	return nil
}

func (s *state) partial(c *app.Ctx, planned *Planned, done, total int, fail *core.Fail) *core.Fail {
	_, notSaved := activate(c, planned, s.recorded, "partial")
	s.report(c, "partial")
	if notSaved != nil {
		fail.Hints = append(fail.Hints, "the partial record was not saved: "+notSaved.Message+" ("+notSaved.Code+")")
	}
	fail.Hints = append(fail.Hints, partialHint(planned, done, total))
	return fail
}

// recordObjects is {handle: {id}} for every recorded handle, sorted.
func recordObjects(recorded canvas.Records) *jsonv.Object {
	handles := make([]string, 0, len(recorded))
	for h := range recorded {
		handles = append(handles, h)
	}
	sort.Strings(handles)
	out := jsonv.NewObject()
	for _, h := range handles {
		out.Set(h, jsonv.Obj("id", recorded[h]))
	}
	return out
}

// report is D7 step 7: HANDLE KIND ACTION ID, then every new secret, once.
func (s *state) report(c *app.Ctx, status string) {
	if c.Out.JSON {
		secrets := make([]any, len(s.secrets))
		for i, secret := range s.secrets {
			secrets[i] = secret
		}
		c.Out.JSONValue(jsonv.Obj("objects", recordObjects(s.recorded), "secrets", secrets, "status", status))
	} else {
		text := core.Render([]string{"HANDLE", "KIND", "ACTION", "ID"}, s.rows)
		if len(s.secrets) > 0 {
			rows := make([][]string, len(s.secrets))
			for i, secret := range s.secrets {
				rows[i] = []string{jsonv.TextOr(secret, "-", "handle"), jsonv.TextOr(secret, "-", "id"), jsonv.TextOr(secret, "-", "value")}
			}
			text += "\n" + core.Render([]string{"HANDLE", "SECRET_ID", "SECRET"}, rows)
		}
		c.Out.Print(text)
	}
	if len(s.secrets) > 0 {
		c.Out.Note("Each secret is shown once: store them now. They cannot be read again.")
	}
}

// runtimeID is the object's runtime_id, set once publishing has projected
// (16.10.26v §5). Still null after ten reads: apply stops, naming it.
func runtimeID(c *app.Ctx, k objects.Kind, platformID string, answer any) (string, *core.Fail) {
	current := answer
	for i := 0; i < publishPolls; i++ {
		if id, ok := jsonv.Str(jsonv.Get(current, "runtime_id")); ok {
			return id, nil
		}
		c.App.HTTP.Clock.Sleep(1)
		var fail *core.Fail
		if current, fail = objects.Get(c, k, platformID); fail != nil {
			return "", fail
		}
	}
	if id, ok := jsonv.Str(jsonv.Get(current, "runtime_id")); ok {
		return id, nil
	}
	return "", core.NewFail(core.ExitUnavailable, "object_projection_pending",
		k.Word+" "+platformID+" is still publishing; nothing after it was applied")
}

// activate is {draft_version, outcome, objects}: runtime ids only
// (16.10.26x D4).
func activate(c *app.Ctx, planned *Planned, recorded canvas.Records, outcome string) (any, *core.Fail) {
	body := jsonv.Obj("draft_version", planned.DraftVersion, "outcome", outcome, "objects", recordObjects(recorded))
	v, fail := c.Call(httpx.Request{Route: httpx.Fixed("canvas.activate"), Vars: []httpx.Pair{{"id", planned.CanvasID}}, JSON: body})
	if fail != nil && fail.Code == "canvas_conflict" {
		fail.Hint("the canvas changed while applying; run `lingara canvas get " + planned.CanvasID + "`")
	}
	return v, fail
}
