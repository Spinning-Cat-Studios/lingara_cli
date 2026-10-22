package apps

import (
	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/core/manifest"
	"getlingara.com/cli/internal/httpx"
	"getlingara.com/cli/internal/jsonv"
)

// ProjectUpdate is lingara apps update [name] without --manifest (ADR
// 16.10.26ae D6): the project's manifest goes to the dev version (PATCH
// …/dev, never its render_url), or with --version becomes an immutable
// draft. A row with no id is registered first.
func ProjectUpdate(c *app.Ctx, a UpdateArgs) *core.Fail {
	t, cwd, fail := Open(c, a.Config)
	if fail != nil {
		return fail
	}
	row, fail := Resolve(t, a.ID, cwd)
	if fail != nil {
		return fail
	}
	m, fail := ReadManifest(t, row, cwd)
	if fail != nil {
		return fail
	}
	if a.Version != nil && manifest.Ephemeral(jsonv.Text(m, "render_url")) {
		return core.Message(core.ExitRenderURLEphemeral, "render_url_ephemeral", "file", Source(t, row, cwd))
	}
	warnings := Warn(c, m)
	if row.ID == "" {
		_, fail := RegisterRow(c, t, row, m)
		return fail
	}
	answer, fail := push(c, row.ID, a.Version, m)
	if fail != nil {
		return fail
	}
	if o := jsonv.AsObject(answer); c.Out.JSON && o != nil {
		o.Set("warnings", warnings)
	}
	c.Out.Value(answer)
	return nil
}

// push is the manifest to the dev version, or with --version a draft.
func push(c *app.Ctx, id string, version *string, m any) (any, *core.Fail) {
	if version != nil {
		return cut(c, id, *version, m)
	}
	return devPatchWithRetry(c, id, func(draftVersion any) any {
		return jsonv.Obj("manifest", m, "draft_version", draftVersion)
	})
}

// cut is step 3: an immutable draft from the project's manifest. The
// server's lifecycle rules stand (a semver not greater than the last, a
// second editable version), as refused.
func cut(c *app.Ctx, id, semver string, m any) (any, *core.Fail) {
	body := jsonv.Obj("semver", semver, "body", jsonv.Obj("manifest", m))
	return c.Call(httpx.Request{Route: httpx.Object("app", "version_create"), Vars: []httpx.Pair{{"id", id}}, JSON: body})
}
