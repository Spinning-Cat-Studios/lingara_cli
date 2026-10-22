package canvas

import (
	"strings"

	"getlingara.com/cli/internal/jsonv"
)

// D7 steps 3–4: the dry-run's verdicts over the plan become this CLI's own
// command lines. A refused verdict stops apply with every refusal named; a
// would_replace, which the token door no longer sends, stops it before any
// write. A would_update webhook carries exactly the flags its changes name:
// --url/--event (a new version that keeps the endpoint and its secret) and
// --enable (no builder spec disables one).

// PartKind says what an argv word is.
type PartKind int

// The four kinds of word.
const (
	// PartText is a literal word.
	PartText PartKind = iota
	// PartID is an id already known: a platform object id.
	PartID
	// PartMade is the id an earlier step of this run makes, by handle.
	PartMade
	// PartManifest is the app op's manifest, written to a file at run time.
	PartManifest
)

// Part is one word of a step's argv.
type Part struct {
	Kind  PartKind
	Value string
}

// Recording is what apply records for a step's node (16.10.26x D4: runtime
// ids only): runtime_id, client_secret (minted_with_client: the secret its
// client's create minted, Of naming that client), secret_id or unchanged.
type Recording struct {
	Kind string
	Of   string
}

// Step is one op as this CLI's own argv; an empty argv makes no call.
type Step struct {
	Handle  string
	Kind    string
	Action  string
	Argv    []Part
	Records Recording
}

// Stop is why apply must stop before writing anything: every refused op
// (handle, code, error), or a would_replace's handle.
type Stop struct {
	Refused [][3]string
	Replace string
}

func word(s string) Part { return Part{Kind: PartText, Value: s} }

func repeated(flag string, values []string) []Part {
	var out []Part
	for _, v := range values {
		out = append(out, word(flag), word(v))
	}
	return out
}

// Steps is every step, or why apply must stop.
func Steps(ops []Op, verdicts []any, live Live, kept Records) ([]Step, *Stop) {
	var refused [][3]string
	for _, v := range verdicts {
		if jsonv.Text(v, "verdict") == "refused" {
			refused = append(refused, [3]string{jsonv.Text(v, "handle"), jsonv.Text(v, "code"), jsonv.Text(v, "error")})
		}
	}
	if len(refused) > 0 {
		return nil, &Stop{Refused: refused}
	}
	for _, v := range verdicts {
		if jsonv.Text(v, "verdict") == "would_replace" {
			return nil, &Stop{Replace: jsonv.Text(v, "handle")}
		}
	}
	steps := make([]Step, 0, len(ops))
	for _, op := range ops {
		steps = append(steps, step(op, verdictOf(verdicts, op.Handle), live, kept, ops))
	}
	return steps, nil
}

func verdictOf(verdicts []any, handle string) any {
	for _, v := range verdicts {
		if h, ok := jsonv.Str(jsonv.Get(v, "handle")); ok && h == handle {
			return v
		}
	}
	return nil
}

// clientRef is the client an op points at: made in this run, or a recorded
// one's platform object id.
func clientRef(handle string, live Live, kept Records, ops []Op) Part {
	for _, o := range ops {
		if o.Handle == handle && o.Op == "create" {
			return Part{Kind: PartMade, Value: handle}
		}
	}
	if record, ok := kept[handle]; ok {
		if id, ok := live.PlatformID("client", record); ok {
			return Part{Kind: PartID, Value: id}
		}
	}
	return Part{Kind: PartMade, Value: handle}
}

func step(op Op, verdict any, live Live, kept Records, ops []Op) Step {
	changes := jsonv.Strings(jsonv.Get(verdict, "changes"))
	s := Step{Handle: op.Handle, Kind: op.Kind, Action: op.Op}
	if op.Op == "update" && len(changes) == 0 {
		s.Action, s.Records = "unchanged", Recording{Kind: "unchanged"}
		return s
	}
	own := Part{Kind: PartMade, Value: op.Handle}
	if id, ok := live.PlatformID(op.Kind, op.ID); op.ID != "" && ok {
		own = Part{Kind: PartID, Value: id}
	}
	client := Part{Kind: PartMade}
	if op.Client != "" {
		client = clientRef(op.Client, live, kept, ops)
	}
	s.Argv, s.Records = argvFor(op, verdict, changes, own, client)
	return s
}

func argvFor(op Op, verdict any, changes []string, own, client Part) ([]Part, Recording) {
	runtime := Recording{Kind: "runtime_id"}
	unchanged := Recording{Kind: "unchanged"}
	switch {
	case op.Kind == "client":
		if op.Op == "create" {
			return clientArgv(op.Body, true, own), runtime
		}
		return clientArgv(op.Body, false, own), unchanged
	case op.Kind == "secret" && jsonv.Text(verdict, "note") == "minted_with_client":
		return nil, Recording{Kind: "client_secret", Of: op.Client}
	case op.Kind == "secret":
		return []Part{word("oauth_client"), word("secret"), word("create"), client}, Recording{Kind: "secret_id"}
	case op.Kind == "webhook" && op.Op == "create":
		return webhookCreate(op.Body, client), runtime
	case op.Kind == "webhook":
		return webhookUpdate(op.Body, changes, own), unchanged
	case op.Op == "create":
		return appCreate(op, client), runtime
	}
	return []Part{word("apps"), word("update"), own, word("--manifest"), {Kind: PartManifest, Value: op.Handle}}, unchanged
}

func clientArgv(body any, create bool, own Part) []Part {
	argv := []Part{word("oauth_client"), word("update"), own}
	if create {
		argv = []Part{word("oauth_client"), word("create")}
	}
	argv = append(argv, word("--name"), word(jsonv.Text(body, "name")))
	argv = append(argv, repeated("--scope", jsonv.Strings(jsonv.Get(body, "scopes")))...)
	return append(argv, repeated("--redirect-uri", jsonv.Strings(jsonv.Get(body, "redirect_uris")))...)
}

func webhookCreate(body any, client Part) []Part {
	argv := []Part{word("webhook"), word("outbound"), word("create"), word("--client"), client}
	argv = append(argv, word("--url"), word(jsonv.Text(body, "url")))
	return append(argv, repeated("--event", jsonv.Strings(jsonv.Get(body, "event_types")))...)
}

func webhookUpdate(body any, changes []string, own Part) []Part {
	argv := []Part{word("webhook"), word("outbound"), word("update"), own}
	has := func(c string) bool {
		for _, x := range changes {
			if x == c {
				return true
			}
		}
		return false
	}
	if has("url") {
		argv = append(argv, word("--url"), word(jsonv.Text(body, "url")))
	}
	if has("event_types") {
		argv = append(argv, repeated("--event", jsonv.Strings(jsonv.Get(body, "event_types")))...)
	}
	if has("enabled") {
		argv = append(argv, word("--enable"))
	}
	return argv
}

func appCreate(op Op, client Part) []Part {
	return []Part{word("apps"), word("create"), word(AppName(op.Body, op.Handle)), word("--client"), client,
		word("--manifest"), {Kind: PartManifest, Value: op.Handle}}
}

// ShellLine is one argv as a shell line, ids not yet made as <handle>.
func ShellLine(argv []Part, manifest func(string) string) string {
	words := make([]string, len(argv))
	for i, p := range argv {
		switch p.Kind {
		case PartMade:
			words[i] = Quote("<" + p.Value + ">")
		case PartManifest:
			words[i] = Quote(manifest(p.Value))
		default:
			words[i] = Quote(p.Value)
		}
	}
	return "lingara " + strings.Join(words, " ")
}

// Quote is POSIX single quotes, only where a word needs them.
func Quote(w string) string {
	if w != "" && strings.Trim(w, plainChars) == "" {
		return w
	}
	return "'" + strings.ReplaceAll(w, "'", `'\''`) + "'"
}

// plainChars are the characters a word may hold and stay unquoted.
const plainChars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_./:@=,+"
