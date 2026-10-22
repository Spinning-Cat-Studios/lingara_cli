package literal

import (
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"

	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/jsonv"
)

// The literal reader against spec/connection-fixtures/, which every kit's
// reader is held to (ADR 16.10.26ae D2).

func fixtures(t *testing.T, kind string) []string {
	t.Helper()
	paths, err := filepath.Glob(filepath.Join("..", "..", "..", "spec", "connection-fixtures", kind, "*.js"))
	if err != nil || len(paths) == 0 {
		t.Fatalf("no %s fixtures: %v", kind, err)
	}
	sort.Strings(paths)
	return paths
}

func expected(path string) (any, bool) {
	data, err := os.ReadFile(strings.TrimSuffix(path, ".js") + ".json")
	if err != nil {
		return nil, false
	}
	v, err := jsonv.Parse(data)
	return v, err == nil
}

// 16.10.26ae AC5 (Go): every valid fixture converts to the wire manifest
// lingara.manifest would write, member order included.
func TestEveryValidFixtureConvertsToItsWireManifest(t *testing.T) {
	for _, path := range fixtures(t, "valid") {
		data, _ := os.ReadFile(path)
		if _, refusal := Statements(string(data)); refusal != nil {
			t.Errorf("%s: %+v", path, refusal)
			continue
		}
		got, refusal := AppManifest(string(data))
		want, has := expected(path)
		switch {
		case !has && refusal == nil:
			t.Errorf("%s has no app, yet converted", path)
		case has && refusal != nil:
			t.Errorf("%s: %+v", path, refusal)
		case has && jsonv.Marshal(got) != jsonv.Marshal(want):
			t.Errorf("%s:\n got %s\nwant %s", path, jsonv.Marshal(got), jsonv.Marshal(want))
		}
	}
}

// 16.10.26ae AC5 (Go): every refused fixture is refused with its code, at
// its position; a scripted statement is named.
func TestEveryRefusedFixtureIsRefusedWhereItSays(t *testing.T) {
	for _, path := range fixtures(t, "refused") {
		data, _ := os.ReadFile(path)
		want, _ := expected(path)
		_, refusal := AppManifest(string(data))
		if refusal == nil {
			t.Errorf("%s was not refused", path)
			continue
		}
		if refusal.Scripted != "" {
			if jsonv.Text(want, "code") != "connection_scripted" || jsonv.Text(want, "statement") != refusal.Scripted {
				t.Errorf("%s: scripted %q", path, refusal.Scripted)
			}
			continue
		}
		line, _ := jsonv.Int(jsonv.Get(want, "line"))
		column, _ := jsonv.Int(jsonv.Get(want, "column"))
		if jsonv.Text(want, "code") != refusal.Code || int(line) != refusal.At.Line || int(column) != refusal.At.Column {
			t.Errorf("%s: got %s at %d:%d", path, refusal.Code, refusal.At.Line, refusal.At.Column)
		}
	}
}

func TestARefusalPrintsItsPositionAndAScriptNamesItsStatement(t *testing.T) {
	fail := (&Refusal{Code: "key", At: Pos{3, 40}}).Fail("lingara/connection.js")
	if !strings.HasPrefix(fail.Message, "lingara/connection.js:3:40: ") || fail.Code != "connection_literal" {
		t.Errorf("%+v", fail)
	}
	scripted := (&Refusal{Scripted: "hooks"}).Fail("lingara/connection.js")
	if scripted.Exit != core.ExitConnectionScripted || !strings.Contains(scripted.Message, "hooks") {
		t.Errorf("%+v", scripted)
	}
}
