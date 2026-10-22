// Package platform is platform.toml (ADR 16.10.26ae D4): the gitignored,
// per-developer file naming the apps a checkout works on. Found by
// --config, then LINGARA_PLATFORM_TOML, then by walking up from the current
// directory as Cargo does. Read in a fixed order, so the first refusal is
// the same in every kit: TOML syntax (platform_toml_schema), a secret-shaped
// value or key (platform_toml_secret), then spec/platform-toml.schema.json
// through santhosh-tekuri/jsonschema (ADR 16.10.26af D2). A tracked file is
// only a warning.
package platform

import (
	"os"
	"path/filepath"
	"sort"
	"strconv"

	"github.com/pelletier/go-toml/v2"

	"getlingara.com/cli/internal/core"
)

// The file names, and the defaults a row falls back to.
const (
	File    = "platform.toml"
	Example = "platform.toml.example"
	// PlaceholderID is the example's id, which every kit reads as no id
	// (spec/templates.md).
	PlaceholderID = "00000000-0000-0000-0000-000000000000"
	DefaultPort   = 8787
)

// Row is one [[apps]] row.
type Row struct {
	Name            string
	ID              string
	Path            string
	Port            int
	Start           string
	RenderPath      string
	Manifest        string
	ManifestCommand string
	Credentials     []string
	EnvFile         string
	// Env is the row's [apps.env], sorted by name.
	Env [][2]string
}

// Toml is a read and checked platform.toml.
type Toml struct {
	File    string
	API     string
	Profile string
	Apps    []Row
}

// Dir is the directory the file is in, which every row's path is relative to.
func (t *Toml) Dir() string {
	return filepath.Dir(t.File)
}

// ProjectDir is the row's project directory.
func (t *Toml) ProjectDir(r Row) string {
	return filepath.Join(t.Dir(), filepath.FromSlash(r.Path))
}

// Shown is the file's name as messages print it: relative to cwd where it
// can be.
func (t *Toml) Shown(cwd string) string {
	return Relative(t.File, cwd)
}

// Relative is path relative to base when base contains it, else path.
func Relative(path, base string) string {
	rel, err := filepath.Rel(base, path)
	if err != nil || rel == ".." || filepath.IsAbs(rel) || len(rel) >= 3 && rel[:3] == ".."+string(filepath.Separator) {
		return path
	}
	if rel == "." {
		return path
	}
	return rel
}

// Locate is --config, then LINGARA_PLATFORM_TOML, then the nearest
// platform.toml at or above cwd; "" when there is none.
func Locate(config string, env core.Env, cwd string) string {
	explicit := config
	if explicit == "" {
		explicit, _ = env.Get("LINGARA_PLATFORM_TOML")
	}
	if explicit == "" {
		return FindUpward(cwd)
	}
	if filepath.IsAbs(explicit) {
		return explicit
	}
	return filepath.Join(cwd, explicit)
}

// FindUpward is the nearest platform.toml in from or a directory above it.
func FindUpward(from string) string {
	for dir := from; ; dir = filepath.Dir(dir) {
		candidate := filepath.Join(dir, File)
		if IsFile(candidate) {
			return candidate
		}
		if filepath.Dir(dir) == dir {
			return ""
		}
	}
}

// IsFile reports whether path is a regular file.
func IsFile(path string) bool {
	info, err := os.Stat(path)
	return err == nil && info.Mode().IsRegular()
}

// SchemaFail is platform_toml_schema for file.
func SchemaFail(file, reason string) *core.Fail {
	return core.Message(core.ExitPlatformTOMLSchema, "platform_toml_schema", "file", file, "reason", reason)
}

// Load reads and checks path; shown is how messages name it.
func Load(path, shown string) (*Toml, *core.Fail) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, SchemaFail(shown, err.Error())
	}
	var doc map[string]any
	if err := toml.Unmarshal(data, &doc); err != nil {
		return nil, SchemaFail(shown, syntaxReason(err))
	}
	if key, found := FirstSecret(data); found {
		return nil, core.Message(core.ExitPlatformTOMLSecret, "platform_toml_secret", "file", shown, "key", key)
	}
	if reason := CheckSchema(doc); reason != "" {
		return nil, SchemaFail(shown, reason)
	}
	return read(path, doc), nil
}

func syntaxReason(err error) string {
	if de, ok := err.(*toml.DecodeError); ok {
		row, col := de.Position()
		return de.Error() + " at line " + strconv.Itoa(row) + ", column " + strconv.Itoa(col)
	}
	return err.Error()
}

func read(path string, doc map[string]any) *Toml {
	t := &Toml{File: path}
	if p, ok := doc["platform"].(map[string]any); ok {
		t.API, _ = p["api"].(string)
		t.Profile, _ = p["profile"].(string)
	}
	rows, _ := doc["apps"].([]any)
	for _, r := range rows {
		if m, ok := r.(map[string]any); ok {
			t.Apps = append(t.Apps, readRow(m))
		}
	}
	return t
}

func readRow(m map[string]any) Row {
	text := func(k string) string { s, _ := m[k].(string); return s }
	r := Row{
		Name: text("name"), ID: text("id"), Path: text("path"), Port: DefaultPort, Start: text("start"),
		RenderPath: text("render_path"), Manifest: text("manifest"), ManifestCommand: text("manifest_command"),
		EnvFile: text("env_file"),
	}
	if r.ID == PlaceholderID {
		r.ID = ""
	}
	if r.Path == "" {
		r.Path = "."
	}
	if port, ok := m["port"].(int64); ok && port > 0 && port <= 65535 {
		r.Port = int(port)
	}
	creds, _ := m["credentials"].([]any)
	for _, c := range creds {
		if s, ok := c.(string); ok {
			r.Credentials = append(r.Credentials, s)
		}
	}
	env, _ := m["env"].(map[string]any)
	for k, v := range env {
		if s, ok := v.(string); ok {
			r.Env = append(r.Env, [2]string{k, s})
		}
	}
	sort.Slice(r.Env, func(i, j int) bool { return r.Env[i][0] < r.Env[j][0] })
	return r
}
