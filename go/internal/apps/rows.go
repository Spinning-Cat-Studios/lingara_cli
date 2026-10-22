package apps

import (
	"os"
	"path/filepath"
	"sort"
	"strings"

	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/assets"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/platform"
)

// Opening platform.toml for a command and picking its row (ADR 16.10.26ae
// D4, D7 step 1). Opening runs every refusal before any request, warns once
// when git tracks the file, and applies its [platform] table: api unless
// LINGARA_API_URL is set, profile unless --profile or LINGARA_PROFILE chose
// one.

// Cwd is the process's directory, through any symlink, so a row's path
// compares.
func Cwd() (string, *core.Fail) {
	dir, err := os.Getwd()
	if err != nil {
		return "", core.Failure("the current directory: " + err.Error())
	}
	return canonical(dir), nil
}

func canonical(path string) string {
	if real, err := filepath.EvalSymlinks(path); err == nil {
		if abs, err := filepath.Abs(real); err == nil {
			return abs
		}
	}
	return path
}

// LoadAt reads and checks the file at path, warns if it is tracked, and
// applies its [platform] table.
func LoadAt(c *app.Ctx, path, cwd string) (*platform.Toml, *core.Fail) {
	shown := platform.Relative(path, cwd)
	t, fail := platform.Load(path, shown)
	if fail != nil {
		return nil, fail
	}
	if platform.Tracked(path) {
		c.Out.Note(assets.Message("platform_toml_tracked", "path", shown))
	}
	if _, set := c.App.Env.Get("LINGARA_API_URL"); !set && t.API != "" {
		c.App.HTTP.API = t.API
	}
	if !c.ProfileChosen && t.Profile != "" {
		c.Profile = t.Profile
	}
	return t, nil
}

// Open is the command's platform.toml, which must exist.
func Open(c *app.Ctx, config string) (*platform.Toml, string, *core.Fail) {
	cwd, fail := Cwd()
	if fail != nil {
		return nil, "", fail
	}
	path := platform.Locate(config, c.App.Env, cwd)
	if path == "" {
		return nil, "", core.Usage("no " + platform.File + " at or above " + cwd + ": run lingara apps create, or lingara apps pin")
	}
	if !platform.IsFile(path) {
		return nil, "", platform.SchemaFail(path, "the file does not exist")
	}
	t, fail := LoadAt(c, path, cwd)
	return t, cwd, fail
}

func unknown(t *platform.Toml, cwd, value string) *core.Fail {
	return core.Message(core.ExitUsage, "app_row_unknown", "file", t.Shown(cwd), "value", value)
}

// Resolve is the row named (by name or id), or with none named: the only
// row, or the one whose project contains cwd (the deepest, when they nest).
func Resolve(t *platform.Toml, wanted, cwd string) (platform.Row, *core.Fail) {
	if wanted != "" {
		for _, r := range t.Apps {
			if r.Name == wanted || r.ID == wanted {
				return r, nil
			}
		}
		return platform.Row{}, unknown(t, cwd, wanted)
	}
	if len(t.Apps) == 1 {
		return t.Apps[0], nil
	}
	if row, ok := deepestContaining(t, cwd); ok {
		return row, nil
	}
	if len(t.Apps) == 0 {
		return platform.Row{}, unknown(t, cwd, "(no app is pinned)")
	}
	names := make([]string, len(t.Apps))
	for i, r := range t.Apps {
		names[i] = r.Name
	}
	return platform.Row{}, core.Message(core.ExitUsage, "app_row_ambiguous", "file", t.Shown(cwd), "names", strings.Join(names, ", "))
}

// deepestContaining is the one row whose project contains cwd, the deepest
// when they nest; false for none, or two at the same depth.
func deepestContaining(t *platform.Toml, cwd string) (platform.Row, bool) {
	type candidate struct {
		depth int
		row   platform.Row
	}
	var containing []candidate
	for _, r := range t.Apps {
		dir := canonical(t.ProjectDir(r))
		if within(cwd, dir) {
			containing = append(containing, candidate{strings.Count(dir, string(filepath.Separator)), r})
		}
	}
	sort.SliceStable(containing, func(i, j int) bool { return containing[i].depth > containing[j].depth })
	if len(containing) == 1 || len(containing) > 1 && containing[1].depth < containing[0].depth {
		return containing[0].row, true
	}
	return platform.Row{}, false
}

// within reports whether path is dir or below it, component-wise.
func within(path, dir string) bool {
	rel, err := filepath.Rel(dir, path)
	return err == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) && !filepath.IsAbs(rel)
}
