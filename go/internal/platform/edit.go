package platform

import (
	"os"
	"path/filepath"
	"strings"

	"getlingara.com/cli/internal/core"
)

// SetID writes id into the row named name: replaces its id line, or adds one
// directly after its name line.
func SetID(file, name, id string) *core.Fail {
	data, err := os.ReadFile(file)
	if err != nil {
		return ioFail(file, err)
	}
	text := string(data)
	start, end, ok := spanOf(text, name)
	if !ok {
		return nil
	}
	ls := lines(text)
	idLine := "id = " + Quote(id)
	inRow := func(l, key string) bool { return strings.HasPrefix(strings.TrimLeft(l, " \t"), key) }
	for i := start; i < end; i++ {
		if inRow(ls[i], "id ") || inRow(ls[i], "id=") {
			ls[i] = idLine
			return write(file, joined(ls))
		}
	}
	after := start
	for i := start; i < end; i++ {
		if inRow(ls[i], "name") {
			after = i
			break
		}
	}
	ls = append(ls[:after+1], append([]string{idLine}, ls[after+1:]...)...)
	return write(file, joined(ls))
}

// RemoveRow removes the row named name; false when there is none.
func RemoveRow(file, name string) (bool, *core.Fail) {
	data, err := os.ReadFile(file)
	if err != nil {
		return false, ioFail(file, err)
	}
	text := string(data)
	start, end, ok := spanOf(text, name)
	if !ok {
		return false, nil
	}
	ls := lines(text)
	if end == len(ls) {
		for start > 0 && strings.TrimSpace(ls[start-1]) == "" {
			start--
		}
	}
	kept := append(append([]string(nil), ls[:start]...), ls[end:]...)
	return true, write(file, joined(kept))
}

// EnsureGitignore adds a platform.toml line to <dir>/.gitignore when no line
// is one.
func EnsureGitignore(dir string) *core.Fail {
	path := filepath.Join(dir, ".gitignore")
	text, fail := readOr(path, "")
	if fail != nil {
		return fail
	}
	for _, l := range lines(text) {
		if strings.TrimSpace(l) == File {
			return nil
		}
	}
	return write(path, text+File+"\n")
}
