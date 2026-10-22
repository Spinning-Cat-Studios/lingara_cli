// Package literal is the connection literal subset (ADR 16.10.26ae D2;
// spec/connection-literal.md): lingara/connection.js read without
// evaluating JavaScript. lex makes the tokens, parse the statements, and
// manifest turns the one lingara.app statement's manifest into the wire
// manifest.
package literal

import (
	"fmt"
	"strings"

	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/jsonv"
)

// Header is the file's first line, byte for byte.
const Header = "// lingara connection v1 (schema 2)"

// Pos is a 1-based line and column.
type Pos struct{ Line, Column int }

// Refusal is why the file is refused: a code at a position, or a scripted
// statement (Scripted names its handle).
type Refusal struct {
	Code     string
	At       Pos
	Scripted string
}

func refuse(code string, at Pos) *Refusal { return &Refusal{Code: code, At: at} }

var what = map[string]string{
	"header":       "the first line must be `// lingara connection v1 (schema 2)`",
	"comment":      "comments go inside a script; the builder rewrites everything outside them",
	"text":         "write text in quotes, with only the escapes JSON knows, and no raw tab or line break",
	"number":       "write finite numbers in decimal, as JSON does",
	"key":          "write each key once",
	"handle":       "this handle is already declared",
	"reference":    "this names no statement declared above it",
	"syntax":       "not part of the literal subset",
	"app_count":    "the file needs exactly one lingara.app statement",
	"option":       "an app takes only client and manifest",
	"manifest_key": "not a manifest key lingara reads, or a required one is missing",
}

// Fail is the refusal as B2 prints it: connection_scripted, else usage with
// `<file>:<line>:<column>: <what> (<code>)`.
func (r *Refusal) Fail(file string) *core.Fail {
	if r.Scripted != "" {
		return core.Message(core.ExitConnectionScripted, "connection_scripted", "file", file, "statement", r.Scripted)
	}
	text, ok := what[r.Code]
	if !ok {
		text = "not part of the literal subset"
	}
	return core.NewFail(core.ExitUsage, "connection_literal",
		fmt.Sprintf("%s:%d:%d: %s (%s)", file, r.At.Line, r.At.Column, text, r.Code))
}

// Lit is one literal value: a string, number (json.Number text), bool,
// null, array, object (members and where its `{` is) or a reference to an
// earlier statement's handle.
type Lit struct {
	Kind    string
	Text    string
	Bool    bool
	Items   []Lit
	Members []Member
	At      Pos
}

// Member is one object member.
type Member struct {
	Key   string
	At    Pos
	Value Lit
}

// Statement is `const <handle> = lingara.<kind>(<value>)`; At is where
// const is.
type Statement struct {
	Handle string
	Kind   string
	Value  Lit
	At     Pos
}

// JSON is the literal as JSON; a reference becomes its handle.
func (l Lit) JSON() any {
	switch l.Kind {
	case "str", "ref":
		return l.Text
	case "num":
		return jsonNumber(l.Text)
	case "bool":
		return l.Bool
	case "arr":
		out := make([]any, len(l.Items))
		for i, item := range l.Items {
			out[i] = item.JSON()
		}
		return out
	case "obj":
		o := jsonv.NewObject()
		for _, m := range l.Members {
			o.Set(m.Key, m.Value.JSON())
		}
		return o
	}
	return nil
}

// Statements is every statement in text.
func Statements(text string) ([]Statement, *Refusal) {
	header, body, _ := strings.Cut(text, "\n")
	if strings.TrimRight(header, "\r") != Header {
		return nil, refuse("header", Pos{1, 1})
	}
	count := len(strings.Split(strings.TrimSuffix(text, "\n"), "\n"))
	toks, refusal := tokens(body, 2)
	if refusal != nil {
		return nil, refusal
	}
	return parseStatements(toks, Pos{count + 1, 1})
}

// onlyApp is the file's one lingara.app statement.
func onlyApp(all []Statement) (Statement, *Refusal) {
	var apps []Statement
	for _, s := range all {
		if s.Kind == "app" {
			apps = append(apps, s)
		}
	}
	switch {
	case len(apps) == 0:
		return Statement{}, refuse("app_count", Pos{1, 1})
	case len(apps) > 1:
		return Statement{}, refuse("app_count", apps[1].At)
	}
	return apps[0], nil
}

// AppManifest is the wire manifest of the file's one lingara.app statement.
func AppManifest(text string) (any, *Refusal) {
	all, refusal := Statements(text)
	if refusal != nil {
		return nil, refusal
	}
	app, refusal := onlyApp(all)
	if refusal != nil {
		return nil, refusal
	}
	if app.Value.Kind != "obj" {
		return nil, refuse("syntax", app.At)
	}
	for _, m := range app.Value.Members {
		if m.Key != "client" && m.Key != "manifest" {
			return nil, refuse("option", m.At)
		}
	}
	for _, m := range app.Value.Members {
		if m.Key == "manifest" && m.Value.Kind == "obj" {
			return convert(m.Value.Members, m.Value.At)
		}
	}
	return nil, refuse("manifest_key", app.Value.At)
}
