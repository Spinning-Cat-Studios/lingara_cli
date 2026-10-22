package core

import (
	"bytes"
	"fmt"
	"io"

	"getlingara.com/cli/internal/jsonv"
)

// Output is contract B1's streams: data to stdout, everything else to
// stderr, and under --json exactly one JSON value on stdout (16.10.26ad
// D6). stdout is buffered and written once, by main, so an in-process verb
// that canvas apply runs (D7 step 4) hands its JSON back instead.
type Output struct {
	Stdout bytes.Buffer
	err    io.Writer
	JSON   bool
}

// NewOutput writes stderr to err.
func NewOutput(err io.Writer, json bool) *Output {
	return &Output{err: err, JSON: json}
}

// Print is text for stdout, as is.
func (o *Output) Print(text string) {
	o.Stdout.WriteString(text)
}

// Note is one line on stderr.
func (o *Output) Note(line string) {
	_, _ = fmt.Fprintln(o.err, line)
}

// Prompt is text on stderr, left on the line it ends.
func (o *Output) Prompt(text string) {
	_, _ = fmt.Fprint(o.err, text)
}

// Value is one server value: as JSON, or as B1's FIELD VALUE table.
func (o *Output) Value(v any) {
	if o.JSON {
		o.JSONValue(v)
		return
	}
	o.Print(Render([]string{"FIELD", "VALUE"}, Fields(v)))
}

// Column is a list's human column: its heading and the member it reads.
type Column struct {
	Heading string
	Key     string
}

// List is one JSON array, or the schema's columns read from each item.
func (o *Output) List(items []any, columns []Column) {
	if o.JSON {
		o.JSONValue(items)
		return
	}
	headings := make([]string, len(columns))
	for i, c := range columns {
		headings[i] = c.Heading
	}
	rows := make([][]string, len(items))
	for r, item := range items {
		rows[r] = make([]string, len(columns))
		for i, c := range columns {
			rows[r][i] = Cell(jsonv.Get(item, c.Key))
		}
	}
	o.Print(Render(headings, rows))
}

// Nothing is a command with nothing to print: nothing, or {} under --json.
func (o *Output) Nothing() {
	if o.JSON {
		o.Print("{}\n")
	}
}

// JSONValue is one compact JSON value and a newline.
func (o *Output) JSONValue(v any) {
	o.Print(jsonv.Marshal(v) + "\n")
}

// ErrorLines is what fail prints on stderr: B2's `error: <text> (<code>)`
// and any advice lines, or under --json one JSON line. What a part-way
// create made is named either way (D2), so nothing it made is left unnamed.
func ErrorLines(fail *Fail, json bool) []string {
	if json {
		return []string{jsonv.Marshal(errorJSON(fail))}
	}
	out := []string{fmt.Sprintf("error: %s (%s)", fail.Message, fail.Code)}
	out = append(out, fail.Hints...)
	for _, m := range fail.Made {
		out = append(out, "made: "+m.Kind+" "+m.ID)
	}
	return out
}

func errorJSON(fail *Fail) *jsonv.Object {
	e := jsonv.Obj("code", fail.Code, "message", fail.Message, "exit", int(fail.Exit))
	if len(fail.Hints) > 0 {
		e.Set("hints", fail.Hints)
	}
	if len(fail.Made) > 0 {
		made := make([]any, len(fail.Made))
		for i, m := range fail.Made {
			made[i] = jsonv.Obj("kind", m.Kind, "id", m.ID)
		}
		e.Set("made", made)
	}
	return jsonv.Obj("error", e)
}
