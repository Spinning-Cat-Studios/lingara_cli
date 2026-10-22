// Package assets is every shared file this kit reads at run time, compiled
// in from _shared/ (ADR 16.10.26af D8): endpoints.json (where production
// is), messages.en.json (what a prompt says, byte for byte), tools.toml
// (cloudflared's floor), platform-toml.schema.json, manifest-warnings.json
// and the app templates. _shared/ is the committed copy `make
// sync-kit-assets` writes and `make check-kit-assets` holds equal to spec/
// and templates/apps/, because a binary built from go/ alone can reach
// nothing above it.
package assets

import (
	"embed"
	"encoding/json"
	"io/fs"
	"sort"
	"strings"

	"github.com/pelletier/go-toml/v2"
)

// The `all:` prefix keeps the templates' `_gitignore` files and the
// directory itself, which embed would otherwise skip as underscore names.
// The underscore is deliberate: the go tool skips `_shared/` in `./...` and
// `go mod tidy`, so the Go templates' `{{name}}` imports never compile.
//
//go:embed all:_shared
var shared embed.FS

// Read is one shared file's bytes, by its path under _shared/.
func Read(name string) []byte {
	data, err := shared.ReadFile("_shared/" + name)
	if err != nil {
		return nil
	}
	return data
}

// Endpoints is endpoints.json: production's addresses and the CLI's own
// first-party client id (16.10.26u §3).
type Endpoints struct {
	ClientID    string `json:"client_id"`
	APIURL      string `json:"api_url"`
	PlatformURL string `json:"platform_url"`
}

// LoadEndpoints reads endpoints.json, falling back to production's values.
func LoadEndpoints() Endpoints {
	e := Endpoints{
		ClientID:    "lingara-cli",
		APIURL:      "https://api.getlingara.com",
		PlatformURL: "https://platform.getlingara.com",
	}
	_ = json.Unmarshal(Read("endpoints.json"), &e)
	return e
}

var texts = loadMessages()

func loadMessages() map[string]string {
	var file struct {
		Messages []struct {
			ID   string `json:"id"`
			Text string `json:"text"`
		} `json:"messages"`
	}
	out := map[string]string{}
	if json.Unmarshal(Read("messages.en.json"), &file) == nil {
		for _, m := range file.Messages {
			out[m.ID] = m.Text
		}
	}
	return out
}

// Message is the message `id` with each `{name}` filled from vars (name,
// value pairs). An id the file lacks prints as itself, which a test pins
// never to happen.
func Message(id string, vars ...string) string {
	text, ok := texts[id]
	if !ok {
		text = id
	}
	for i := 0; i+1 < len(vars); i += 2 {
		text = strings.ReplaceAll(text, "{"+vars[i]+"}", vars[i+1])
	}
	return text
}

// HasMessage reports whether messages.en.json carries id.
func HasMessage(id string) bool {
	_, ok := texts[id]
	return ok
}

// CloudflaredMinVersion is tools.toml's `[cloudflared] min_version`, as
// dotted numbers.
func CloudflaredMinVersion() []uint64 {
	var tools struct {
		Cloudflared struct {
			MinVersion string `toml:"min_version"`
		} `toml:"cloudflared"`
	}
	_ = toml.Unmarshal(Read("tools.toml"), &tools)
	return DottedVersion(tools.Cloudflared.MinVersion)
}

// DottedVersion is `a.b.c` as numbers; nil when any field is not one.
func DottedVersion(text string) []uint64 {
	var out []uint64
	for _, field := range strings.Split(text, ".") {
		n, ok := parseUint(field)
		if !ok {
			return nil
		}
		out = append(out, n)
	}
	return out
}

func parseUint(s string) (uint64, bool) {
	if s == "" {
		return 0, false
	}
	var n uint64
	for _, c := range s {
		if c < '0' || c > '9' {
			return 0, false
		}
		n = n*10 + uint64(c-'0')
	}
	return n, true
}

// TemplateFile is one file of a template, its path relative to the
// template's root (`<lang>/<variant>/`).
type TemplateFile struct {
	Path  string
	Bytes []byte
}

// Template is every file of templates/apps/<lang>/<variant>/, sorted by
// path, as the Rust kit's build.rs indexes them.
func Template(lang, variant string) []TemplateFile {
	root := "_shared/templates/apps/" + lang + "/" + variant
	var out []TemplateFile
	_ = fs.WalkDir(shared, root, func(path string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		data, readErr := shared.ReadFile(path)
		if readErr == nil {
			out = append(out, TemplateFile{Path: strings.TrimPrefix(path, root+"/"), Bytes: data})
		}
		return nil
	})
	sort.Slice(out, func(i, j int) bool { return out[i].Path < out[j].Path })
	return out
}
