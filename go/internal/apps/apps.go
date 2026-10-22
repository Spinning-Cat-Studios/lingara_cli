// Package apps is lingara apps … (16.10.26ad D1, D2; 16.10.26ae). create
// and update each have one dispatch point on whether --manifest is present:
// with it, 16.10.26ad's registration path; without it, 16.10.26ae's
// scaffolding and project-reading paths. dev, status, pin and unpin read
// platform.toml.
package apps

import (
	"os"

	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/core/manifest"
	"getlingara.com/cli/internal/httpx"
	"getlingara.com/cli/internal/jsonv"
	"getlingara.com/cli/internal/objects"
)

// Columns is apps list's.
var Columns = objects.Columns("ID", "id", "NAME", "name", "CLIENT", "parent_id", "SERVING", "serving_version", "VISIBILITY", "visibility")

var versionColumns = objects.Columns("SEMVER", "semver", "STATE", "state", "STATE_AT", "state_at", "REVIEWED", "review_basis")

// CreateArgs is apps create's argument and flags; "" and nil are not given.
type CreateArgs struct {
	Name        string
	Client      string
	Manifest    string
	Lang        string
	Bare        bool
	Dir         string
	NoRegister  bool
	Description *string
	Port        int
	Slots       []string
	Context     []string
	Config      string
}

// CreateEither is the one rule conflicts cannot say: --manifest and
// --client come together (the registration form) or not at all (the
// scaffold).
func CreateEither(c *app.Ctx, a CreateArgs) *core.Fail {
	switch {
	case a.Manifest != "" && a.Client != "":
		_, fail := Register(c, a.Name, a.Client, a.Manifest)
		return fail
	case a.Manifest == "" && a.Client == "":
		return Scaffold(c, a)
	}
	return core.Usage("--manifest and --client go together: both register an app from a file, neither scaffolds a project")
}

// ManifestBody is a --manifest file's JSON as the app body's manifest.
func ManifestBody(path string) (any, *core.Fail) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, core.Usage(path + ": " + err.Error())
	}
	m, err := jsonv.Parse(data)
	if err != nil {
		return nil, core.Usage(path + " is not JSON: " + err.Error())
	}
	return m, nil
}

// Register is the registration form: register the object, write no files.
func Register(c *app.Ctx, name, client, path string) (any, *core.Fail) {
	m, fail := ManifestBody(path)
	if fail != nil {
		return nil, fail
	}
	made := &objects.Creation{}
	created, fail := made.Object(c, objects.App, name, client, jsonv.Obj("manifest", m))
	return made.Conclude(c, objects.App, jsonv.Text(created, "id"), fail)
}

// UpdateArgs is apps update's argument and flags.
type UpdateArgs struct {
	ID       string
	Manifest string
	Version  *string
	Config   string
}

// Update dispatches on --manifest.
func Update(c *app.Ctx, a UpdateArgs) *core.Fail {
	if a.Manifest == "" {
		return ProjectUpdate(c, a)
	}
	return updateFromFile(c, a)
}

// updateFromFile is apps update <id> --manifest <path>: a new version from
// the file. A quick-tunnel or dev.invalid render URL is refused before any
// request (16.10.26ad D2 with 16.10.26ae D6 step 3's guard).
func updateFromFile(c *app.Ctx, a UpdateArgs) *core.Fail {
	if a.ID == "" {
		return core.Usage("apps update --manifest needs the app's id")
	}
	m, fail := ManifestBody(a.Manifest)
	if fail != nil {
		return fail
	}
	if manifest.Ephemeral(jsonv.Text(m, "render_url")) {
		return core.Message(core.ExitRenderURLEphemeral, "render_url_ephemeral", "file", a.Manifest)
	}
	answer, fail := objects.BodyUpdate(c, objects.App, a.ID, func(body *jsonv.Object) { body.Set("manifest", jsonv.Clone(m)) })
	if fail != nil {
		return fail
	}
	c.Out.Value(answer)
	return nil
}

// Submit is {"visibility": "public"}: review itself is the operator's, in
// the portal.
func Submit(c *app.Ctx, id, version string) *core.Fail {
	vars := []httpx.Pair{{"id", id}, {"semver", version}}
	answer, fail := c.Call(httpx.Request{Route: httpx.Object("app", "submit"), Vars: vars, JSON: jsonv.Obj("visibility", "public")})
	if fail != nil {
		return fail
	}
	c.Out.Value(answer)
	return nil
}

// VersionOp is versions publish or withdraw.
func VersionOp(c *app.Ctx, op, id, semver string) *core.Fail {
	answer, fail := objects.VersionOp(c, objects.App, op, id, semver)
	if fail != nil {
		return fail
	}
	c.Out.Value(answer)
	return nil
}

// ListVersions is every page, newest first; next_cursor is the last semver
// seen.
func ListVersions(c *app.Ctx, id string) *core.Fail {
	items, fail := objects.Pages(c, httpx.Object("app", "versions"), []httpx.Pair{{"id", id}}, nil)
	if fail != nil {
		return fail
	}
	c.Out.List(items, versionColumns)
	return nil
}
