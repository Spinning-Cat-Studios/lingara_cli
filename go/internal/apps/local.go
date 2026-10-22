package apps

import (
	"path/filepath"
	"strings"

	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/jsonv"
	"getlingara.com/cli/internal/objects"
	"getlingara.com/cli/internal/platform"
)

// The local verbs (ADR 16.10.26ae D4, D9): apps status reads the dev
// version; apps pin, apps unpin and apps list --pinned edit or read
// platform.toml alone (pin's one read is app.get for an id).

// Status is live with its lease, parked with its last URL, or the serving
// version; --json is the GET …/dev body as the server answered it.
func Status(c *app.Ctx, name, config string) *core.Fail {
	t, cwd, fail := Open(c, config)
	if fail != nil {
		return fail
	}
	row, fail := Resolve(t, name, cwd)
	if fail != nil {
		return fail
	}
	if row.ID == "" {
		return core.Usage(row.Name + " is not registered yet: run lingara apps update " + row.Name)
	}
	view, fail := devGet(c, row.ID)
	if fail != nil {
		return fail
	}
	if c.Out.JSON {
		c.Out.JSONValue(view)
		return nil
	}
	c.Out.Print(StatusLine(view) + "\n")
	return nil
}

// StatusLine is D9's three lines. The lease time is HH:MM of the server's
// UTC stamp.
func StatusLine(view any) string {
	url, ok := jsonv.Str(jsonv.Get(view, "render_url"))
	if !ok {
		return "dev: serving version"
	}
	if jsonv.Text(view, "session", "state") == "live" {
		lease := "?"
		if stamp, ok := jsonv.Str(jsonv.Get(view, "session", "lease_expires_at")); ok && len(stamp) >= 16 {
			lease = stamp[11:16]
		}
		return "dev: live " + url + " (lease until " + lease + " UTC)"
	}
	return "dev: parked (last " + url + ")"
}

// uuidShaped is 8-4-4-4-12 hex; anything else names a local row.
func uuidShaped(s string) bool {
	groups := strings.Split(s, "-")
	lens := []int{8, 4, 4, 4, 12}
	if len(groups) != len(lens) {
		return false
	}
	for i, g := range groups {
		if len(g) != lens[i] || strings.Trim(g, "0123456789abcdefABCDEF") != "" {
			return false
		}
	}
	return true
}

// handleOf is a platform object's name as a local handle: `Luba Dive Log`
// is luba-dive-log.
func handleOf(name string) string {
	lowered := strings.Map(func(r rune) rune {
		if r < 128 && (r >= 'a' && r <= 'z' || r >= '0' && r <= '9') {
			return r
		}
		if r >= 'A' && r <= 'Z' {
			return r + ('a' - 'A')
		}
		return '-'
	}, name)
	return strings.Join(strings.FieldsFunc(lowered, func(r rune) bool { return r == '-' }), "-")
}

// pinTarget is the file pin writes: the one Open would find, else a new
// one here; and the names it already pins.
func pinTarget(c *app.Ctx, config, cwd string) (string, []string, *core.Fail) {
	path := platform.Locate(config, c.App.Env, cwd)
	if path == "" || !platform.IsFile(path) {
		if path == "" {
			path = filepath.Join(cwd, platform.File)
		}
		return path, nil, nil
	}
	t, fail := LoadAt(c, path, cwd)
	if fail != nil {
		return "", nil, fail
	}
	names := make([]string, len(t.Apps))
	for i, r := range t.Apps {
		names[i] = r.Name
	}
	return path, names, nil
}

// pinName is the row name and id an argument pins: an id is read for its
// name; a name needs --path.
func pinName(c *app.Ctx, appArg, path string) (string, string, *core.Fail) {
	if !uuidShaped(appArg) {
		if path == "" {
			return "", "", core.Usage("pinning " + appArg + " by name needs --path <dir>")
		}
		return appArg, "", nil
	}
	object, fail := objects.Get(c, objects.App, appArg)
	if fail != nil {
		return "", "", fail
	}
	return handleOf(jsonv.Text(object, "name")), appArg, nil
}

// Pin is apps pin <id|name> [--path <dir>]: a row in platform.toml and,
// when it has none of that name, in the example. Never a platform write.
func Pin(c *app.Ctx, appArg, path, config string) *core.Fail {
	cwd, fail := Cwd()
	if fail != nil {
		return fail
	}
	name, id, fail := pinName(c, appArg, path)
	if fail != nil {
		return fail
	}
	if !platform.AppNameOK(name) {
		return core.Message(core.ExitUsage, "app_name_invalid", "name", name)
	}
	file, names, fail := pinTarget(c, config, cwd)
	if fail != nil {
		return fail
	}
	for _, n := range names {
		if n == name {
			return core.Usage(platform.Relative(file, cwd) + " already pins " + name)
		}
	}
	if path == "" {
		path = "."
	}
	project := canonical(join(cwd, path))
	row := platform.NewRow{Name: name, Path: relativeSlash(project, filepath.Dir(file)), Port: platform.DefaultPort}
	if fail := platform.PinRow(file, row, id); fail != nil {
		return fail
	}
	c.Out.Nothing()
	return nil
}

// Unpin is apps unpin <name>: platform.toml only; the shared example
// changes by review.
func Unpin(c *app.Ctx, name, config string) *core.Fail {
	t, cwd, fail := Open(c, config)
	if fail != nil {
		return fail
	}
	removed, fail := platform.RemoveRow(t.File, name)
	if fail != nil {
		return fail
	}
	if !removed {
		return core.Message(core.ExitUsage, "app_row_unknown", "file", t.Shown(cwd), "value", name)
	}
	c.Out.Nothing()
	return nil
}

// ListPinned is apps list --pinned: the rows, as JSON or as a NAME/ID/PATH/
// PORT table.
func ListPinned(c *app.Ctx, config string) *core.Fail {
	t, _, fail := Open(c, config)
	if fail != nil {
		return fail
	}
	rows := make([]any, len(t.Apps))
	for i, r := range t.Apps {
		var id any
		if r.ID != "" {
			id = r.ID
		}
		rows[i] = jsonv.Obj("name", r.Name, "id", id, "path", r.Path, "port", r.Port)
	}
	if c.Out.JSON {
		c.Out.JSONValue(rows)
		return nil
	}
	c.Out.List(rows, objects.Columns("NAME", "name", "ID", "id", "PATH", "path", "PORT", "port"))
	return nil
}

// join is dir/path, or path when it is absolute.
func join(dir, path string) string {
	if filepath.IsAbs(path) {
		return path
	}
	return filepath.Join(dir, path)
}

// relativeSlash is dir relative to base, /-separated; . when they are one;
// dir itself when base does not contain it.
func relativeSlash(dir, base string) string {
	base = canonical(base)
	if !within(dir, base) {
		return dir
	}
	rel, _ := filepath.Rel(base, dir)
	return filepath.ToSlash(rel)
}
