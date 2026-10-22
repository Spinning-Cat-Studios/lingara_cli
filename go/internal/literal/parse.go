package literal

import "encoding/json"

// Statements and values over the tokens: `const <handle> =
// lingara.<kind>(<value>)`, one after another. A second call argument is a
// script, which refuses the file there and then.

var kinds = map[string]bool{"client": true, "secret": true, "webhook": true, "app": true}

type parser struct {
	toks    []token
	i       int
	handles map[string]bool
	end     Pos
}

func jsonNumber(text string) json.Number { return json.Number(text) }

func (p *parser) peekPunct(c string) bool {
	return p.i < len(p.toks) && p.toks[p.i].kind == "punct" && p.toks[p.i].text == c
}

func (p *parser) next() (token, *Refusal) {
	if p.i >= len(p.toks) {
		return token{}, refuse("syntax", p.end)
	}
	p.i++
	return p.toks[p.i-1], nil
}

func (p *parser) expect(kind, text string) (token, *Refusal) {
	t, refusal := p.next()
	if refusal != nil {
		return t, refusal
	}
	if t.kind != kind || (text != "" && t.text != text) {
		return t, refuse("syntax", t.at)
	}
	return t, nil
}

func (p *parser) statement() (Statement, *Refusal) {
	steps := []struct{ kind, text string }{{"ident", "const"}, {"ident", ""}, {"punct", "="}, {"ident", "lingara"}, {"punct", "."}, {"ident", ""}, {"punct", "("}}
	var got [7]token
	for i, s := range steps {
		t, refusal := p.expect(s.kind, s.text)
		if refusal != nil {
			return Statement{}, refusal
		}
		got[i] = t
		if i == 1 && p.handles[t.text] {
			return Statement{}, refuse("handle", t.at)
		}
		if i == 5 && !kinds[t.text] {
			return Statement{}, refuse("syntax", t.at)
		}
	}
	value, refusal := p.value()
	if refusal != nil {
		return Statement{}, refusal
	}
	if p.peekPunct(",") {
		return Statement{}, &Refusal{Scripted: got[1].text}
	}
	if _, refusal := p.expect("punct", ")"); refusal != nil {
		return Statement{}, refusal
	}
	// Declared only now: a value may name the statements above it, never
	// its own.
	p.handles[got[1].text] = true
	return Statement{Handle: got[1].text, Kind: got[5].text, Value: value, At: got[0].at}, nil
}

func (p *parser) value() (Lit, *Refusal) {
	t, refusal := p.next()
	if refusal != nil {
		return Lit{}, refusal
	}
	switch t.kind {
	case "str", "num":
		return Lit{Kind: t.kind, Text: t.text}, nil
	case "ident":
		return p.word(t)
	}
	switch t.text {
	case "[":
		items, refusal := p.items("]", p.value)
		return Lit{Kind: "arr", Items: items}, refusal
	case "{":
		members, refusal := p.object()
		return Lit{Kind: "obj", Members: members, At: t.at}, refusal
	}
	return Lit{}, refuse("syntax", t.at)
}

// word is true, false, null, or a reference to a handle declared above.
func (p *parser) word(t token) (Lit, *Refusal) {
	switch {
	case t.text == "true" || t.text == "false":
		return Lit{Kind: "bool", Bool: t.text == "true"}, nil
	case t.text == "null":
		return Lit{Kind: "null"}, nil
	case p.handles[t.text]:
		return Lit{Kind: "ref", Text: t.text}, nil
	}
	return Lit{}, refuse("reference", t.at)
}

// items is `item {, item} [,] close`, the opener already read.
func items[T any](p *parser, close string, item func() (T, *Refusal)) ([]T, *Refusal) {
	var out []T
	for {
		if p.peekPunct(close) {
			p.i++
			return out, nil
		}
		v, refusal := item()
		if refusal != nil {
			return nil, refusal
		}
		out = append(out, v)
		t, refusal := p.next()
		if refusal != nil {
			return nil, refusal
		}
		if t.kind != "punct" || (t.text != close && t.text != ",") {
			return nil, refuse("syntax", t.at)
		}
		if t.text == close {
			return out, nil
		}
	}
}

func (p *parser) items(close string, item func() (Lit, *Refusal)) ([]Lit, *Refusal) {
	return items(p, close, item)
}

func (p *parser) object() ([]Member, *Refusal) {
	seen := map[string]bool{}
	return items(p, "}", func() (Member, *Refusal) {
		t, refusal := p.next()
		if refusal != nil {
			return Member{}, refusal
		}
		if t.kind != "ident" && t.kind != "str" {
			return Member{}, refuse("syntax", t.at)
		}
		if seen[t.text] {
			return Member{}, refuse("key", t.at)
		}
		seen[t.text] = true
		if _, refusal := p.expect("punct", ":"); refusal != nil {
			return Member{}, refusal
		}
		v, refusal := p.value()
		return Member{Key: t.text, At: t.at, Value: v}, refusal
	})
}

func parseStatements(toks []token, end Pos) ([]Statement, *Refusal) {
	p := &parser{toks: toks, handles: map[string]bool{}, end: end}
	var out []Statement
	for p.i < len(p.toks) {
		s, refusal := p.statement()
		if refusal != nil {
			return nil, refusal
		}
		out = append(out, s)
	}
	return out, nil
}
