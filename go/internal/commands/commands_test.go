package commands

import (
	"bytes"
	"strings"
	"testing"

	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/core/store"
	"getlingara.com/cli/internal/httpx"
	"getlingara.com/cli/internal/jsonv"
)

// noNetwork fails any request: these runs must reach no server.
type noNetwork struct{ sent int }

func (n *noNetwork) Send(httpx.WireRequest) (httpx.WireResponse, error) {
	n.sent++
	return httpx.WireResponse{Status: 599}, nil
}

func testApp(net *noNetwork, dir string) *app.App {
	return &app.App{
		HTTP:   &httpx.HTTP{Transport: net, Clock: httpx.SystemClock{}, API: "http://127.0.0.1:9", UserAgent: "lingara-cli/0.0.0 (go; test)"},
		Env:    core.Env{},
		Stores: &store.Stores{Mode: store.ModeFile, Keychain: &store.Memory{}, File: store.NewFileStore(dir)},
	}
}

func run(t *testing.T, argv ...string) (app.Outcome, string, int) {
	t.Helper()
	net := &noNetwork{}
	var stderr bytes.Buffer
	outcome := Run(testApp(net, t.TempDir()), argv, &stderr)
	return outcome, stderr.String(), net.sent
}

// 16.10.26af AC7: `lingara --version` is `lingara <VERSION> (go)` and
// nothing else on stdout.
func TestVersionNamesTheKit(t *testing.T) {
	outcome, _, _ := run(t, "--version")
	if string(outcome.Stdout) != "lingara "+Version+" (go)\n" || outcome.Exit != core.ExitOk {
		t.Fatalf("%q %v", outcome.Stdout, outcome.Exit)
	}
}

// D9: cobra's defaults switched off. Every parse failure is usage (2),
// reaches no server, and a bare group is a usage error, not help and 0.
func TestCobraDefaultsAreOverridden(t *testing.T) {
	cases := [][]string{
		{"nope"}, {"apps"}, {"apps", "nope"}, {"apps", "list", "--nope"}, {"apps", "get"}, {"apps", "get", "a", "b"},
		{"client", "create", "--name", "x", "--ci", "--type", "public"}, {"client", "create"},
		{"oauth_client", "create", "--name", "x", "--type", "secretive"}, {"apps", "create", "x", "--port", "0"},
		{"canvas", "apply", "c", "--dry-run", "--print-commands"}, {},
	}
	for _, argv := range cases {
		outcome, stderr, sent := run(t, argv...)
		if outcome.Exit != core.ExitUsage || sent != 0 || len(outcome.Stdout) != 0 || !strings.HasPrefix(stderr, "error: ") {
			t.Errorf("%v: exit %d, sent %d, stdout %q, stderr %q", argv, outcome.Exit, sent, outcome.Stdout, stderr)
		}
	}
	outcome, stderr, _ := run(t, "apps", "list", "--nope", "--json")
	v, err := jsonv.Parse([]byte(stderr))
	if outcome.Exit != core.ExitUsage || err != nil || jsonv.Text(v, "error", "code") != "usage" {
		t.Errorf("--json usage error: %q", stderr)
	}
}

// 16.10.26af AC6 (Go): __tree reads positionals and coarse types from the
// kit-local table, and every table row is a registered leaf.
func TestTheTreeReadsTheKitLocalTable(t *testing.T) {
	outcome, _, _ := run(t, "__tree")
	tree, err := jsonv.Parse(outcome.Stdout)
	if err != nil {
		t.Fatal(err)
	}
	seen := map[string]bool{}
	walk(tree, "", seen)
	for path := range kitTable {
		if path != "__tree" && !seen[path] {
			t.Errorf("%s is in the table but not registered", path)
		}
	}
	if !strings.Contains(string(outcome.Stdout), `"name":"lang","type":"enum"`) ||
		!strings.Contains(string(outcome.Stdout), `"name":"semver","type":"string","required":true`) {
		t.Errorf("tree: %s", outcome.Stdout)
	}
}

func walk(node any, at string, seen map[string]bool) {
	prefix := func(name string) string { return strings.TrimSpace(at + " " + name) }
	for _, c := range jsonv.Arr(jsonv.Get(node, "commands")) {
		seen[prefix(jsonv.Text(c, "name"))] = true
	}
	for _, t := range jsonv.Arr(jsonv.Get(node, "topics")) {
		walk(t, prefix(jsonv.Text(t, "name")), seen)
	}
}
