package platform

import (
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"getlingara.com/cli/internal/core"
)

// Writing platform.toml, its example and .gitignore, byte for byte as
// spec/templates.md says, so every kit leaves the same files behind. The
// edits are line-based on purpose: a developer's comments and layout
// outside the touched row survive.

// FileHeader starts a new platform.toml.
const FileHeader = "# platform.toml — gitignored; copy from platform.toml.example.\n" +
	"# Never put a secret here. lingara refuses to read a value that looks like one.\n" +
	"schema = 1\n"

// ExampleHeader starts a new platform.toml.example.
const ExampleHeader = "# platform.toml.example — checked in. Copy it to platform.toml (gitignored),\n" +
	"# then run lingara apps update to register your own private copy of each app.\n" +
	"schema = 1\n"

// NewRow is a row as create and pin write it. ManifestKey is "manifest" or
// "manifest_command", or "" for neither.
type NewRow struct {
	Name, Path    string
	Port          int
	Start         string
	ManifestKey   string
	ManifestValue string
}

// Quote is a TOML basic string.
func Quote(s string) string {
	return `"` + strings.ReplaceAll(strings.ReplaceAll(s, `\`, `\\`), `"`, `\"`) + `"`
}

func rowText(row NewRow, id string) string {
	var b strings.Builder
	b.WriteString("\n[[apps]]\nname = " + Quote(row.Name) + "\n")
	if id != "" {
		b.WriteString("id = " + Quote(id) + "\n")
	}
	b.WriteString("path = " + Quote(row.Path) + "\nport = " + strconv.Itoa(row.Port) + "\n")
	if row.Start != "" {
		b.WriteString("start = " + Quote(row.Start) + "\n")
	}
	if row.ManifestKey != "" {
		b.WriteString(row.ManifestKey + " = " + Quote(row.ManifestValue) + "\n")
	}
	return b.String()
}

func ioFail(path string, err error) *core.Fail {
	return core.Failure(path + ": " + err.Error())
}

func readOr(path, header string) (string, *core.Fail) {
	data, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		return header, nil
	}
	if err != nil {
		return "", ioFail(path, err)
	}
	text := string(data)
	if text != "" && !strings.HasSuffix(text, "\n") {
		text += "\n"
	}
	return text, nil
}

func write(path, text string) *core.Fail {
	if err := os.WriteFile(path, []byte(text), 0o644); err != nil {
		return ioFail(path, err)
	}
	return nil
}

// lines is text's lines, as Rust's str::lines splits them.
func lines(text string) []string {
	out := strings.Split(text, "\n")
	if len(out) > 0 && out[len(out)-1] == "" {
		out = out[:len(out)-1]
	}
	for i, l := range out {
		out[i] = strings.TrimSuffix(l, "\r")
	}
	return out
}

// HasRow reports whether text holds a row named name.
func HasRow(text, name string) bool {
	_, _, ok := spanOf(text, name)
	return ok
}

// PinRow appends the row to file (with id, if any) and, when the example
// beside it has no row of that name, to the example (with the placeholder).
func PinRow(file string, row NewRow, id string) *core.Fail {
	text, fail := readOr(file, FileHeader)
	if fail != nil {
		return fail
	}
	if fail := write(file, text+rowText(row, id)); fail != nil {
		return fail
	}
	example := filepath.Join(filepath.Dir(file), Example)
	current, fail := readOr(example, ExampleHeader)
	if fail != nil {
		return fail
	}
	if HasRow(current, row.Name) {
		return nil
	}
	return write(example, current+rowText(row, PlaceholderID))
}

// rows is each [[apps]] row's line span [start, end): up to the next table
// header that is not its own [apps.env].
func rows(ls []string) [][2]int {
	header := func(l string) bool {
		return strings.HasPrefix(strings.TrimLeft(l, " \t"), "[") && strings.TrimSpace(l) != "[apps.env]"
	}
	var out [][2]int
	for s, l := range ls {
		if strings.TrimSpace(l) != "[[apps]]" {
			continue
		}
		end := len(ls)
		for i := s + 1; i < len(ls); i++ {
			if header(ls[i]) {
				end = i
				break
			}
		}
		out = append(out, [2]int{s, end})
	}
	return out
}

func spanOf(text, name string) (int, int, bool) {
	ls := lines(text)
	want := "name = " + Quote(name)
	for _, span := range rows(ls) {
		for _, l := range ls[span[0]:span[1]] {
			if strings.TrimSpace(l) == want {
				return span[0], span[1], true
			}
		}
	}
	return 0, 0, false
}

func joined(ls []string) string {
	var b strings.Builder
	for _, l := range ls {
		b.WriteString(l + "\n")
	}
	return b.String()
}
