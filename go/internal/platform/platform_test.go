package platform

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"getlingara.com/cli/internal/core"
)

func writeFixture(t *testing.T, text string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), File)
	if err := os.WriteFile(path, []byte(text), 0o644); err != nil {
		t.Fatal(err)
	}
	return path
}

// 16.10.26ae AC6 (Go): syntax, then secrets in file order, then the JSON
// Schema; a secret refusal names the key, never the value.
func TestTheFirstRefusalIsTheSameInEveryKit(t *testing.T) {
	cases := []struct {
		text string
		exit core.Exit
		key  string
	}{
		{"schema = ", core.ExitPlatformTOMLSchema, ""},
		{"schema = 2\n", core.ExitPlatformTOMLSchema, ""},
		{"schema = 1\nother = 1\n", core.ExitPlatformTOMLSchema, ""},
		{"schema = 1\n[[apps]]\nname = \"Luba\"\npath = \".\"\n", core.ExitPlatformTOMLSchema, ""},
		{"schema = 1\n[[apps]]\nname = \"luba\"\npath = \".\"\nport = 0\n", core.ExitPlatformTOMLSchema, ""},
		{"schema = 1\n[[apps]]\nname = \"luba\"\npath = \".\"\nmanifest = \"a\"\nmanifest_command = \"b\"\n", core.ExitPlatformTOMLSchema, ""},
		{"schema = 2\n[[apps]]\nname = \"x\"\n[apps.env]\nAPI_TOKEN = \"a\"\nb_secret = \"c\"\n", core.ExitPlatformTOMLSecret, "apps.env.API_TOKEN"},
		{"schema = 1\n[[apps]]\nname = \"luba\"\npath = \".\"\nenv = { HOOK = \"lgr_whsec_conformanceLeaked\" }\n", core.ExitPlatformTOMLSecret, "apps.env.HOOK"},
	}
	for _, c := range cases {
		if _, fail := Load(writeFixture(t, c.text), File); !refusedAs(fail, c.exit, c.key) {
			t.Errorf("%q: %+v", c.text, fail)
		}
	}
	ok := "schema = 1\n[platform]\napi = \"https://a.example\"\n[[apps]]\nname = \"luba\"\nid = \"00000000-0000-0000-0000-000000000000\"\n" +
		"path = \".\"\n[apps.env]\nLINGARA_CLIENT_ID = \"lgr_cid_conformance_app\"\nTOKENIZER = \"bpe\"\n"
	toml, fail := Load(writeFixture(t, ok), File)
	if fail != nil || toml.API != "https://a.example" || len(toml.Apps) != 1 || toml.Apps[0].ID != "" || toml.Apps[0].Port != DefaultPort {
		t.Fatalf("accepted file: %+v %+v", toml, fail)
	}
}

// refusedAs is the refusal wanted, naming key and never a secret's value.
func refusedAs(fail *core.Fail, exit core.Exit, key string) bool {
	return fail != nil && fail.Exit == exit && strings.Contains(fail.Message, key) && !strings.Contains(fail.Message, "lgr_whsec")
}

func TestSecretShapes(t *testing.T) {
	for _, s := range []string{"lgr_cs_abcdefgh", "x lgr_whsec_ABCDEFGH12 y"} {
		if !SecretValue(s) {
			t.Errorf("%q is a secret", s)
		}
	}
	for _, s := range []string{"lgr_cid_abcdefghij", "lgr_cs_short", "lgr__abcdefghij", "hunter2"} {
		if SecretValue(s) {
			t.Errorf("%q is not a secret", s)
		}
	}
	if !SecretKey("CLIENT_SECRET") || !SecretKey("api_token") || SecretKey("TOKENIZER") {
		t.Error("secret keys are whole words")
	}
}

func TestRowsArePinnedAndUnpinnedByLine(t *testing.T) {
	dir := t.TempDir()
	file := filepath.Join(dir, File)
	row := NewRow{Name: "luba", Path: ".", Port: 8787, Start: "npm start", ManifestKey: "manifest", ManifestValue: "lingara/connection.js"}
	if fail := PinRow(file, row, ""); fail != nil {
		t.Fatal(fail)
	}
	if fail := SetID(file, "luba", "7d1e"); fail != nil {
		t.Fatal(fail)
	}
	data, _ := os.ReadFile(file)
	want := FileHeader + "\n[[apps]]\nname = \"luba\"\nid = \"7d1e\"\npath = \".\"\nport = 8787\nstart = \"npm start\"\nmanifest = \"lingara/connection.js\"\n"
	if string(data) != want {
		t.Fatalf("got %q", data)
	}
	example, _ := os.ReadFile(filepath.Join(dir, Example))
	if !strings.Contains(string(example), PlaceholderID) {
		t.Fatalf("example: %q", example)
	}
	if removed, _ := RemoveRow(file, "luba"); !removed {
		t.Fatal("not removed")
	}
	if data, _ := os.ReadFile(file); string(data) != FileHeader {
		t.Fatalf("after unpin: %q", data)
	}
	if fail := EnsureGitignore(dir); fail != nil {
		t.Fatal(fail)
	}
	if data, _ := os.ReadFile(filepath.Join(dir, ".gitignore")); string(data) != "platform.toml\n" {
		t.Fatalf(".gitignore: %q", data)
	}
}
