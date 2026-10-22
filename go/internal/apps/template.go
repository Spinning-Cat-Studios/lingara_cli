package apps

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/pelletier/go-toml/v2"

	"getlingara.com/cli/internal/assets"
	"getlingara.com/cli/internal/core"
)

// Rendering a template (ADR 16.10.26ae D2, D3; spec/templates.md): the
// fixed {{key}} set, the array syntax each language's bare template uses,
// the two renaming rules, and template.toml, read and never written.

// Keys is what the scaffold substitutes, each derived once.
type Keys struct {
	Name, DisplayName, Description string
	Port                           int
	Slots, Context                 []string
}

// meta is template.toml: the row's start command and its manifest source
// (manifestKey is "manifest" or "manifest_command").
type meta struct {
	start                      string
	manifestKey, manifestValue string
}

type rendered struct {
	path  string
	bytes []byte
}

const metaFile = "template.toml"

// array is ["a", "b"] in the syntax the template's language writes an
// array in.
func array(lang, variant string, items []string) string {
	quoted := make([]string, len(items))
	for i, item := range items {
		quoted[i] = `"` + item + `"`
	}
	inner := strings.Join(quoted, ", ")
	if variant == "bare" {
		switch lang {
		case "go":
			return "[]string{" + inner + "}"
		case "java":
			return "List.of(" + inner + ")"
		case "kotlin":
			return "listOf(" + inner + ")"
		}
	}
	return "[" + inner + "]"
}

// projectPath: a segment starting _ is written with .; a .tmpl suffix is
// dropped.
func projectPath(relative string) string {
	segments := strings.Split(relative, "/")
	for i, s := range segments {
		if strings.HasPrefix(s, "_") {
			segments[i] = "." + s[1:]
		}
	}
	last := len(segments) - 1
	segments[last] = strings.TrimSuffix(segments[last], ".tmpl")
	return filepath.Join(segments...)
}

func substitute(text, lang, variant string, k Keys) string {
	pairs := [][2]string{
		{"{{name}}", k.Name},
		{"{{display_name}}", k.DisplayName},
		{"{{description}}", k.Description},
		{"{{port}}", strconv.Itoa(k.Port)},
		{"{{slots}}", array(lang, variant, k.Slots)},
		{"{{context}}", array(lang, variant, k.Context)},
	}
	for _, p := range pairs {
		text = strings.ReplaceAll(text, p[0], p[1])
	}
	return text
}

// render is every file of <lang>/<variant>, rendered, and its template.toml.
func render(lang, variant string, k Keys) ([]rendered, meta, *core.Fail) {
	files := assets.Template(lang, variant)
	if len(files) == 0 {
		return nil, meta{}, core.Failure("this lingara carries no " + lang + " " + variant + " template")
	}
	var m meta
	var out []rendered
	for _, f := range files {
		if f.Path == metaFile {
			var fail *core.Fail
			if m, fail = parseMeta(f.Bytes); fail != nil {
				return nil, meta{}, fail
			}
			continue
		}
		out = append(out, rendered{path: projectPath(f.Path), bytes: []byte(substitute(string(f.Bytes), lang, variant, k))})
	}
	return out, m, nil
}

func parseMeta(data []byte) (meta, *core.Fail) {
	var file struct {
		Start           string `toml:"start"`
		Manifest        string `toml:"manifest"`
		ManifestCommand string `toml:"manifest_command"`
	}
	if err := toml.Unmarshal(data, &file); err != nil {
		return meta{}, core.Failure(metaFile + ": " + err.Error())
	}
	m := meta{start: file.Start}
	switch {
	case file.Manifest != "":
		m.manifestKey, m.manifestValue = "manifest", file.Manifest
	case file.ManifestCommand != "":
		m.manifestKey, m.manifestValue = "manifest_command", file.ManifestCommand
	}
	return m, nil
}

// writeAll writes every file under dir, creating directories as it goes.
func writeAll(dir string, files []rendered) *core.Fail {
	for _, f := range files {
		path := filepath.Join(dir, f.path)
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			return core.Failure(filepath.Dir(path) + ": " + err.Error())
		}
		if err := os.WriteFile(path, f.bytes, 0o644); err != nil {
			return core.Failure(path + ": " + err.Error())
		}
	}
	return nil
}
