package apps

import (
	"os"
	"path/filepath"
	"strings"
	"unicode"

	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/assets"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/platform"
)

// lingara apps create <name> without --manifest (ADR 16.10.26ae D1):
// scaffold offline, pin the project in platform.toml, then register it as a
// private app unless --no-register or there is no session.

const (
	defaultDescription = "A Lingara app."
	defaultSlot        = "home.side"
)

// descriptionOK: the description goes inside a string literal in every
// template language.
func descriptionOK(text string) bool {
	return !strings.ContainsAny(text, "\"\\`$#{}") && strings.IndexFunc(text, unicode.IsControl) < 0
}

func sliceOK(kind string) bool {
	if kind == "" || kind[0] < 'a' || kind[0] > 'z' {
		return false
	}
	return strings.Trim(kind, "abcdefghijklmnopqrstuvwxyz_") == ""
}

func keys(a CreateArgs) (Keys, *core.Fail) {
	if !platform.AppNameOK(a.Name) {
		return Keys{}, core.Message(core.ExitUsage, "app_name_invalid", "name", a.Name)
	}
	description := defaultDescription
	if a.Description != nil {
		description = *a.Description
	}
	if !descriptionOK(description) {
		return Keys{}, core.Usage("--description may not hold \", \\, `, $, #, { or }, or a control character")
	}
	for _, c := range a.Context {
		if !sliceOK(c) {
			return Keys{}, core.Usage("--context " + c + " is not a context slice name")
		}
	}
	slots := a.Slots
	if len(slots) == 0 {
		slots = []string{defaultSlot}
	}
	port := a.Port
	if port == 0 {
		port = platform.DefaultPort
	}
	context := a.Context
	if context == nil {
		context = []string{}
	}
	return Keys{Name: a.Name, DisplayName: DisplayName(a.Name), Description: description, Port: port, Slots: slots, Context: context}, nil
}

// pinFile is step 3's file: --config or LINGARA_PLATFORM_TOML, else the
// nearest above the project's parent, else a new one inside the project.
func pinFile(c *app.Ctx, a CreateArgs, dir, cwd string) (string, *core.Fail) {
	found := ""
	if _, set := c.App.Env.Get("LINGARA_PLATFORM_TOML"); a.Config != "" || set {
		found = platform.Locate(a.Config, c.App.Env, cwd)
	} else {
		found = platform.FindUpward(filepath.Dir(dir))
	}
	if found == "" || !platform.IsFile(found) {
		return filepath.Join(dir, platform.File), nil
	}
	t, fail := LoadAt(c, found, cwd)
	if fail != nil {
		return "", fail
	}
	for _, r := range t.Apps {
		if r.Name == a.Name {
			return "", core.Usage(platform.Relative(found, cwd) + " already pins " + a.Name)
		}
	}
	return found, nil
}

func notEmpty(dir string) bool {
	entries, err := os.ReadDir(dir)
	return err == nil && len(entries) > 0
}

// Scaffold is apps create's project form.
func Scaffold(c *app.Ctx, a CreateArgs) *core.Fail {
	k, fail := keys(a)
	if fail != nil {
		return fail
	}
	cwd, fail := Cwd()
	if fail != nil {
		return fail
	}
	dir := filepath.Join(cwd, a.Name)
	if a.Dir != "" {
		dir = join(cwd, a.Dir)
	}
	if notEmpty(dir) {
		return core.Message(core.ExitUsage, "dir_not_empty", "path", platform.Relative(dir, cwd))
	}
	lang, variant := a.Lang, "default"
	if lang == "" {
		lang = "typescript"
	}
	if a.Bare {
		variant = "bare"
	}
	files, m, fail := render(lang, variant, k)
	if fail != nil {
		return fail
	}
	file, fail := pinFile(c, a, dir, cwd)
	if fail != nil {
		return fail
	}
	return writeProject(c, a, project{dir: dir, cwd: cwd, file: file, files: files, meta: m, port: k.Port})
}

type project struct {
	dir, cwd, file string
	files          []rendered
	meta           meta
	port           int
}

func writeProject(c *app.Ctx, a CreateArgs, p project) *core.Fail {
	if err := os.MkdirAll(p.dir, 0o755); err != nil {
		return core.Failure(p.dir + ": " + err.Error())
	}
	dir := canonical(p.dir)
	if fail := writeAll(dir, p.files); fail != nil {
		return fail
	}
	base := canonical(filepath.Dir(p.file))
	if filepath.Dir(p.file) == p.dir {
		base = dir
	}
	row := platform.NewRow{Name: a.Name, Path: relativeSlash(dir, base), Port: p.port, Start: p.meta.start,
		ManifestKey: p.meta.manifestKey, ManifestValue: p.meta.manifestValue}
	file := filepath.Join(base, filepath.Base(p.file))
	if fail := platform.PinRow(file, row, ""); fail != nil {
		return fail
	}
	if fail := platform.EnsureGitignore(base); fail != nil {
		return fail
	}
	return register(c, a, file, p.cwd)
}

// register is step 2, last: offline when --no-register, or with no session
// to use.
func register(c *app.Ctx, a CreateArgs, file, cwd string) *core.Fail {
	if a.NoRegister {
		c.Out.Nothing()
		return nil
	}
	if _, _, ci := c.CIPair(); !ci {
		record, fail := c.App.Stores.Load(c.Profile)
		if fail != nil {
			return fail
		}
		if record == nil {
			c.Out.Note(assets.Message("apps_create_login_hint", "name", a.Name))
			c.Out.Nothing()
			return nil
		}
	}
	t, fail := LoadAt(c, file, cwd)
	if fail != nil {
		return fail
	}
	row, fail := Resolve(t, a.Name, cwd)
	if fail != nil {
		return fail
	}
	m, fail := ReadManifest(t, row, cwd)
	if fail != nil {
		return fail
	}
	Warn(c, m)
	_, fail = RegisterRow(c, t, row, m)
	return fail
}
