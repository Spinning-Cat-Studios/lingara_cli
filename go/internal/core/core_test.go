package core_test

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/core/canvas"
	"getlingara.com/cli/internal/core/store"
	"getlingara.com/cli/internal/jsonv"
)

// 16.10.26af AC16
// TestCore is the Go kit's core, as the AC names it: the confirm ritual,
// the store selection (keychain, file, LINGARA_KEYRING), the classifier's
// B2 rules, every canvas-plans.json vector, the B1 table, and interrupted
// returned as 130 on SIGINT.
func TestCore(t *testing.T) {
	t.Run("confirm ritual", testConfirmRitual)
	t.Run("store selection", testStoreSelection)
	t.Run("classifier B2 rules", testClassifier)
	t.Run("canvas plan vectors", testCanvasVectors)
	t.Run("B1 table", testTable)
	t.Run("interrupted is 130 on SIGINT", testInterrupted)
}

// ritual is a scripted terminal, stderr and developer.
type ritual struct {
	tty    bool
	lines  []string
	stderr strings.Builder
	emails int
}

func (r *ritual) Interactive() bool { return r.tty }

func (r *ritual) ReadLine() (string, bool) {
	if len(r.lines) == 0 {
		return "", false
	}
	line := r.lines[0]
	r.lines = r.lines[1:]
	return line + "\n", true
}

func (r *ritual) Prompt(text string) { r.stderr.WriteString(text) }

func (r *ritual) Email() (string, *core.Fail) {
	r.emails++
	return "dev@conformance.invalid", nil
}

func run(r *ritual, yes bool, confirm *string) (string, *core.Fail, int) {
	reads := 0
	email, fail := core.ConfirmDestructive(r, core.ConfirmFlags{Yes: yes, Confirm: confirm}, func() (core.Prompt, *core.Fail) {
		reads++
		return core.Prompt{Kind: "OAuth client", Name: "My server", ID: "oc1", Consequence: "Its tokens stop.", PinnedBy: 2}, nil
	})
	return email, fail, reads
}

func code(fail *core.Fail) string {
	if fail == nil {
		return ""
	}
	return fail.Code
}

func ptr(s string) *string { return &s }

// outcome is one ritual's observable result.
type outcome struct {
	email, code string
	reads       int
}

func ritualOutcome(r *ritual, yes bool, confirm *string) outcome {
	email, fail, reads := run(r, yes, confirm)
	return outcome{email: email, code: code(fail), reads: reads}
}

func check(t *testing.T, got, want outcome, what string) {
	t.Helper()
	if got != want {
		t.Errorf("%s: got %+v, want %+v", what, got, want)
	}
}

func testConfirmRitual(t *testing.T) {
	t.Run("interactive", testRitualInteractive)
	t.Run("flagged", testRitualFlagged)
	if core.EmailHint("dev@example.com") != "d\u2026@example.com" || core.EmailHint("d@example.com") != "\u2026@example.com" {
		t.Fatal("the hint shows one letter and the domain")
	}
}

func testRitualInteractive(t *testing.T) {
	// y, then the email in another case with spaces: proceeds, canonical.
	r := &ritual{tty: true, lines: []string{"y", "  Dev@Conformance.INVALID "}}
	check(t, ritualOutcome(r, false, nil), outcome{email: "dev@conformance.invalid", reads: 1}, "tty ok")
	prompts := r.stderr.String()
	if !strings.Contains(prompts, "Delete OAuth client My server (oc1)?\nIts tokens stop.\nObjects pinning it: 2. [y/N] ") {
		t.Errorf("y/n prompt: %q", prompts)
	}
	if !strings.Contains(prompts, "Type your developer email (d\u2026@conformance.invalid) to confirm: ") {
		t.Errorf("email prompt: %q", prompts)
	}
	// n, nothing, or anything else: declined, exit 6, the email never asked.
	for _, answer := range []string{"n", "", "yep"} {
		r := &ritual{tty: true, lines: []string{answer}}
		check(t, ritualOutcome(r, false, nil), outcome{code: "confirm_declined", reads: 1}, answer)
		if r.emails != 0 {
			t.Errorf("%q asked for the email", answer)
		}
	}
	// A mismatch: exit 6, no retry.
	r = &ritual{tty: true, lines: []string{"y", "someone@else.invalid", "dev@conformance.invalid"}}
	check(t, ritualOutcome(r, false, nil), outcome{code: "confirm_email_mismatch", reads: 1}, "tty mismatch")
	_, fail, _ := run(&ritual{tty: true, lines: []string{"n"}}, false, nil)
	if fail.Exit != core.ExitNotConfirmed {
		t.Errorf("declined exits %d", fail.Exit)
	}
}

func testRitualFlagged(t *testing.T) {
	// -y and a matching --confirm: no prompt and no read, in a pipe or a terminal.
	for _, tty := range []bool{false, true} {
		r := &ritual{tty: tty}
		check(t, ritualOutcome(r, true, ptr("DEV@conformance.invalid")), outcome{email: "dev@conformance.invalid"}, "flagged")
		if r.stderr.Len() != 0 {
			t.Errorf("flagged prompted: %q", r.stderr.String())
		}
	}
	check(t, ritualOutcome(&ritual{}, true, ptr("someone@else.invalid")), outcome{code: "confirm_email_mismatch"}, "flagged mismatch")
	// One flag alone, in a pipe: refused before any call, the email included.
	for _, one := range []struct {
		yes     bool
		confirm *string
	}{{true, nil}, {false, ptr("dev@conformance.invalid")}, {false, nil}} {
		r := &ritual{}
		check(t, ritualOutcome(r, one.yes, one.confirm), outcome{code: "confirm_non_tty_refused"}, "one flag")
		if r.emails != 0 {
			t.Errorf("one flag %+v read the email", one)
		}
	}
}

func record(email string) store.ProfileRecord {
	return store.ProfileRecord{Email: email, AccessToken: "lgr_at_x", RefreshToken: "lgr_rt_x"}
}

func stores(mode store.Mode, keychain *store.Memory, dir string) *store.Stores {
	return &store.Stores{Mode: mode, Keychain: keychain, File: store.NewFileStore(filepath.Join(dir, "lingara"))}
}

func exists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

func testStoreSelection(t *testing.T) {
	t.Run("keychain first", testKeychainFirst)
	t.Run("file fallback warns once", testFileFallback)
	t.Run("LINGARA_KEYRING", testKeyringVariable)
	t.Run("fixture and delete", testFixtureAndDelete)
}

func testKeychainFirst(t *testing.T) {
	// A keychain that is there: used, and the file is never written.
	present := stores(store.ModeAuto, &store.Memory{}, t.TempDir())
	if warning, fail := present.Save("default", record("a@b.c")); warning != "" || fail != nil {
		t.Fatalf("keychain save: %q %v", warning, fail)
	}
	got, _ := present.Load("default")
	if got == nil || got.Email != "a@b.c" {
		t.Fatalf("keychain load: %+v", got)
	}
	if exists(present.File.Path) {
		t.Fatal("the file was written beside a keychain")
	}
}

func testFileFallback(t *testing.T) {
	// No keychain: the file, with exactly one warning for the profile.
	dir := t.TempDir()
	fallback := stores(store.ModeAuto, &store.Memory{Missing: true}, dir)
	if warning, _ := fallback.Save("default", record("a@b.c")); !strings.Contains(warning, "LINGARA_KEYRING=keychain") {
		t.Fatalf("first fallback warns: %q", warning)
	}
	if warning, _ := fallback.Save("default", record("a@b.c")); warning != "" {
		t.Fatalf("warned twice: %q", warning)
	}
	checkModes(t, fallback.File.Path)
	// Nothing secret ever reaches config.toml: no store writes it.
	if exists(filepath.Join(dir, "lingara", "config.toml")) {
		t.Fatal("a store wrote config.toml")
	}
}

func testKeyringVariable(t *testing.T) {
	// LINGARA_KEYRING=file: the file, silently.
	dir := t.TempDir()
	if warning, fail := stores(store.ModeFromEnv("file"), &store.Memory{}, dir).Save("p", record("x@y.z")); warning != "" || fail != nil {
		t.Fatalf("file mode: %q %v", warning, fail)
	}
	// LINGARA_KEYRING=keychain with no keychain: auth_required, no fallback.
	strict := stores(store.ModeFromEnv("keychain"), &store.Memory{Missing: true}, dir)
	if _, fail := strict.Save("p", record("x@y.z")); fail == nil || fail.Exit != core.ExitAuthRequired {
		t.Fatalf("strict keychain: %v", fail)
	}
	if _, fail := strict.Load("p"); fail == nil {
		t.Fatal("strict keychain load fell back")
	}
	if store.ModeFromEnv("") != store.ModeAuto || store.ModeFromEnv("other") != store.ModeAuto {
		t.Fatal("anything else is auto")
	}
}

func checkModes(t *testing.T, path string) {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	shape, _ := jsonv.Parse(data)
	if n, _ := jsonv.Int(jsonv.Get(shape, "schema")); n != 1 || jsonv.Text(shape, "profiles", "default", "email") != "a@b.c" {
		t.Fatalf("file shape: %s", data)
	}
	if runtime.GOOS == "windows" {
		return
	}
	file, _ := os.Stat(path)
	parent, _ := os.Stat(filepath.Dir(path))
	if file.Mode().Perm() != 0o600 || parent.Mode().Perm() != 0o700 {
		t.Fatalf("modes %o %o", file.Mode().Perm(), parent.Mode().Perm())
	}
}

// testFixtureAndDelete: the committed conformance fixture reads back as
// the default profile, and delete forgets a profile and the empty file.
func testFixtureAndDelete(t *testing.T) {
	dir := t.TempDir()
	fixture, err := os.ReadFile(filepath.Join("..", "..", "..", "conformance", "fixtures", "credentials", "stored.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "credentials.json"), fixture, 0o600); err != nil {
		t.Fatal(err)
	}
	stored, err := store.NewFileStore(dir).Load("default")
	if err != nil || stored == nil || stored.Email != "dev@conformance.invalid" || stored.AccessExpiresAt != "2099-01-01T00:00:00Z" {
		t.Fatalf("fixture: %+v %v", stored, err)
	}
	s := stores(store.ModeFile, &store.Memory{}, t.TempDir())
	_, _ = s.Save("a", record("a@b.c"))
	_, _ = s.Save("b", record("b@b.c"))
	_ = s.Delete("a")
	if a, _ := s.Load("a"); a != nil {
		t.Fatal("a survived its delete")
	}
	_ = s.Delete("b")
	if exists(s.File.Path) {
		t.Fatal("the empty file survived")
	}
}

func verdict(route string, status int, body string) core.Verdict {
	token := route == "oauth.token" || route == "oauth.device_authorization"
	platform := strings.Contains(route, ".") && !token && !strings.HasPrefix(route, "canvas.")
	return core.Classify(core.Answer{Route: route, TokenRoute: token, PlatformRoute: platform, Bearer: !token, Status: status, Body: []byte(body)})
}

func testClassifier(t *testing.T) {
	held := `{"code":"dev_session_held","error":"x","lease_expires_at":"2026-10-20T15:00:00Z"}`
	cases := []struct {
		route  string
		status int
		body   string
		want   core.Exit
	}{
		{"app.list", 404, `{"code":"platform_disabled","error":"x"}`, core.ExitPlatformDisabled},
		{"developer.me", 403, `{"code":"platform_access_not_enabled","error":"x"}`, core.ExitPlatformDisabled},
		{"oauth.token", 400, `{"error":"invalid_grant","lingara_code":"platform_access_not_enabled"}`, core.ExitPlatformDisabled},
		{"oauth.token", 400, `{"error":"invalid_grant","error_description":"x"}`, core.ExitAuthRequired},
		{"oauth.token", 400, `{"error":"invalid_grant","lingara_code":"developer_account_required"}`, core.ExitAuthRequired},
		{"app.get", 404, `{"code":"object_not_found","error":"x"}`, core.ExitNotFound},
		{"developer.me", 404, `{"code":"developer_not_found","error":"x"}`, core.ExitRefused},
		{"app.get", 404, `{"code":"developer_not_found","error":"x"}`, core.ExitNotFound},
		{"app.list", 404, "", core.ExitFailure},
		{"oauth.token", 401, `{"error":"invalid_client"}`, core.ExitAuthRequired},
		{"oauth.token", 429, `{"error":"slow"}`, core.ExitUnavailable},
		{"oauth.token", 503, `{"error":"down"}`, core.ExitUnavailable},
		{"app.list", 405, "", core.ExitFailure},
		{"app.list", 404, "<html>", core.ExitFailure},
		{"app.create", 400, `{"code":"c","error":"e"}`, core.ExitRefused},
		{"app.create", 403, `{"code":"c","error":"e"}`, core.ExitRefused},
		{"app.create", 409, `{"code":"c","error":"e"}`, core.ExitRefused},
		{"app.create", 422, `{"code":"c","error":"e"}`, core.ExitRefused},
		{"app.list", 429, "", core.ExitUnavailable},
		{"app.list", 503, "down for maintenance", core.ExitUnavailable},
		{"app_dev.session_open", 409, held, core.ExitDevSessionHeld},
		{"app_dev.patch", 409, held, core.ExitDevSessionHeld},
		{"app_dev.session_renew", 409, `{"code":"dev_session_not_live","error":"x"}`, core.ExitDevSessionLost},
		{"app_dev.session_renew", 409, `{"code":"dev_session_expired","error":"x"}`, core.ExitDevSessionLost},
		{"app.create", 409, held, core.ExitRefused},
		{"app_dev.patch", 409, `{"code":"version_conflict","error":"x"}`, core.ExitRefused},
	}
	for _, c := range cases {
		if got := verdict(c.route, c.status, c.body); got.Refresh || got.Exit != c.want {
			t.Errorf("%s %d %s: got %+v, want %s", c.route, c.status, c.body, got, c.want.Name())
		}
	}
	if !verdict("app.list", 401, "").Refresh {
		t.Error("a bearer 401 refreshes")
	}
	a := core.Answer{Route: "app.get", Status: 404, Body: []byte(`{"code":"object_not_found","error":"No such object."}`)}
	if f := core.FailFor(a, core.ExitNotFound); f.Code != "object_not_found" || f.Message != "No such object." {
		t.Errorf("the printed refusal keeps the server code: %+v", f)
	}
	limited := core.Answer{Route: "app.create", Status: 429, RetryAfter: 30, HasRetry: true}
	if f := core.FailFor(limited, core.ExitUnavailable); !strings.HasSuffix(f.Message, "(retry after 30 s)") {
		t.Errorf("retry after: %q", f.Message)
	}
}

// testInterrupted: a claimed signal ends a wait with exit 130, returned,
// never a death by signal (ADR 16.10.26af D9).
func testInterrupted(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("Windows has no SIGINT to send to oneself")
	}
	core.WatchSignals(nil)
	release := core.Claim()
	defer release()
	defer core.ResetStopped()
	self, _ := os.FindProcess(os.Getpid())
	go func() {
		time.Sleep(100 * time.Millisecond)
		_ = self.Signal(os.Interrupt)
	}()
	fail := core.SleepUnlessStopped(10 * time.Second)
	if fail == nil || fail.Exit != core.ExitInterrupted || int(fail.Exit) != 130 || fail.Exit.Name() != "interrupted" {
		t.Fatalf("got %+v", fail)
	}
}

func testTable(t *testing.T) {
	text := core.Render([]string{"ID", "NAME", "SERVING"}, [][]string{{"7d1e", "Flashcards", "1.0.0"}, {"7d1e2f30-long", "Tone drills", ""}})
	if text != "ID             NAME         SERVING\n7d1e           Flashcards   1.0.0\n7d1e2f30-long  Tone drills  -\n" {
		t.Fatalf("table: %q", text)
	}
	long := strings.Repeat("x", 200)
	if !strings.Contains(core.Render([]string{"A"}, [][]string{{long}}), long) {
		t.Fatal("a cell was truncated")
	}
	if got := core.Render([]string{"SCOPES", "B"}, [][]string{{"a", "b"}}); got != "SCOPES  B\na       b\n" {
		t.Fatalf("a wide header: %q", got)
	}
	v, _ := jsonv.Parse([]byte(`[null, [], ["a:read","b:write"], true, 3, {"a":1}]`))
	want := []string{"-", "-", "a:read,b:write", "true", "3", `{"a":1}`}
	for i, item := range jsonv.Arr(v) {
		if core.Cell(item) != want[i] {
			t.Errorf("cell %d: %q", i, core.Cell(item))
		}
	}
	fields, _ := jsonv.Parse([]byte(`{"id":"x","name":null}`))
	if rows := core.Fields(fields); len(rows) != 2 || rows[0][1] != "x" || rows[1][1] != "-" {
		t.Errorf("fields: %v", rows)
	}
}

func testCanvasVectors(t *testing.T) {
	data, err := os.ReadFile(filepath.Join("..", "..", "..", "conformance", "vectors", "canvas-plans.json"))
	if err != nil {
		t.Fatal(err)
	}
	file, err := jsonv.Parse(data)
	if err != nil {
		t.Fatal(err)
	}
	vectors := jsonv.Arr(jsonv.Get(file, "vectors"))
	if len(vectors) < 8 {
		t.Fatalf("every rule has its vector: %d", len(vectors))
	}
	for _, v := range vectors {
		checkVector(t, v)
	}
}

func checkVector(t *testing.T, v any) {
	t.Helper()
	name := jsonv.Text(v, "name")
	def := canvas.ReadDefinition(jsonv.Get(v, "definition"))
	live := liveOf(jsonv.Get(v, "live"))
	kept := canvas.StillLive(def, recordsOf(jsonv.Get(v, "recorded")), live)
	ops, refusal := canvas.Build(def, kept)
	if refusal != nil {
		if refusal.Kind != "script" || jsonv.Text(v, "expect", "refused") != refusal.Handle {
			t.Errorf("%s: refused %+v", name, refusal)
		}
		return
	}
	keptJSON := jsonv.NewObject()
	for h, id := range kept {
		keptJSON.Set(h, id)
	}
	if !jsonv.Equal(keptJSON, jsonv.Get(v, "expect", "kept")) {
		t.Errorf("%s: kept %s", name, jsonv.Marshal(keptJSON))
	}
	wire := make([]any, len(ops))
	for i, op := range ops {
		wire[i] = op.Wire()
	}
	if !jsonv.Equal(wire, jsonv.Get(v, "expect", "ops")) {
		t.Errorf("%s: ops %s", name, jsonv.Marshal(wire))
	}
	checkSteps(t, v, ops, plan{live: live, kept: kept})
}

// plan is what the planner kept, and the live lists it kept it against.
type plan struct {
	live canvas.Live
	kept canvas.Records
}

func checkSteps(t *testing.T, v any, ops []canvas.Op, p plan) {
	t.Helper()
	name := jsonv.Text(v, "name")
	verdicts, ok := jsonv.Get(v, "verdicts").([]any)
	if !ok {
		return
	}
	steps, stop := canvas.Steps(ops, verdicts, p.live, p.kept)
	if stop != nil {
		got := jsonv.Obj("replace", stop.Replace)
		if stop.Replace == "" {
			handles := []string{}
			for _, r := range stop.Refused {
				handles = append(handles, r[0])
			}
			got = jsonv.Obj("refused", handles)
		}
		if !jsonv.Equal(got, jsonv.Get(v, "stop")) {
			t.Errorf("%s: stop %s", name, jsonv.Marshal(got))
		}
		return
	}
	rendered := make([]any, len(steps))
	for i, s := range steps {
		rendered[i] = renderStep(s)
	}
	if !jsonv.Equal(rendered, jsonv.Get(v, "steps")) {
		t.Errorf("%s: steps %s", name, jsonv.Marshal(rendered))
	}
}

func renderStep(s canvas.Step) *jsonv.Object {
	argv := []string{}
	for _, p := range s.Argv {
		switch p.Kind {
		case canvas.PartMade:
			argv = append(argv, "<"+p.Value+">")
		case canvas.PartManifest:
			argv = append(argv, "<"+p.Value+".manifest>")
		default:
			argv = append(argv, p.Value)
		}
	}
	return jsonv.Obj("handle", s.Handle, "action", s.Action, "records", s.Records.Kind, "argv", argv)
}

func recordsOf(v any) canvas.Records {
	out := canvas.Records{}
	o := jsonv.AsObject(v)
	for _, h := range o.Keys() {
		out[h] = jsonv.Text(o.Get(h), "id")
	}
	return out
}

func liveOf(v any) canvas.Live {
	rows := func(k string) []canvas.Row {
		var out []canvas.Row
		for _, item := range jsonv.Arr(jsonv.Get(v, k)) {
			out = append(out, canvas.ReadRow(item))
		}
		return out
	}
	return canvas.Live{Clients: rows("oauth_client"), Webhooks: rows("webhook_out"), Apps: rows("app")}
}
