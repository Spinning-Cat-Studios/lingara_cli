package literal

import "strings"

// The literal subset's tokens: identifiers, JSON-escaped strings in either
// quote, finite JSON numbers and punctuation, each with its 1-based line
// and column. A comment, a template literal or a character the subset does
// not know stops the scan.

type token struct {
	kind string // ident, str, num, punct
	text string
	at   Pos
}

type lexer struct {
	chars []rune
	i     int
	line  int
	col   int
}

func (lx *lexer) pos() Pos { return Pos{lx.line, lx.col} }

func (lx *lexer) peek(ahead int) (rune, bool) {
	if lx.i+ahead < len(lx.chars) {
		return lx.chars[lx.i+ahead], true
	}
	return 0, false
}

func (lx *lexer) bump() (rune, bool) {
	c, ok := lx.peek(0)
	if !ok {
		return 0, false
	}
	lx.i++
	if c == '\n' {
		lx.line++
		lx.col = 1
	} else {
		lx.col++
	}
	return c, true
}

func (lx *lexer) takeWhile(keep func(rune) bool) string {
	var b strings.Builder
	for c, ok := lx.peek(0); ok && keep(c); c, ok = lx.peek(0) {
		b.WriteRune(c)
		lx.bump()
	}
	return b.String()
}

func identStart(c rune) bool {
	return c < 128 && (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') || c == '_' || c == '$'
}

func identChar(c rune) bool {
	return identStart(c) || c >= '0' && c <= '9'
}

// tokens is every token of text, which starts at line firstLine.
func tokens(text string, firstLine int) ([]token, *Refusal) {
	lx := &lexer{chars: []rune(text), line: firstLine, col: 1}
	var out []token
	for c, more := lx.peek(0); more; c, more = lx.peek(0) {
		at := lx.pos()
		if strings.ContainsRune(" \t\r\n", c) {
			lx.bump()
			continue
		}
		tok, refusal := lx.next(c, at)
		if refusal != nil {
			return nil, refusal
		}
		out = append(out, tok)
	}
	return out, nil
}

func (lx *lexer) next(c rune, at Pos) (token, *Refusal) {
	next, _ := lx.peek(1)
	switch {
	case c == '/' && (next == '/' || next == '*'):
		return token{}, refuse("comment", at)
	case c == '`':
		return token{}, refuse("text", at)
	case c == '"' || c == '\'':
		s, refusal := lx.str(at)
		return token{kind: "str", text: s, at: at}, refusal
	case c == '-' || c >= '0' && c <= '9':
		n, refusal := lx.number(at)
		return token{kind: "num", text: n, at: at}, refusal
	}
	return lx.word(c, at)
}

// word is an identifier or one punctuation character.
func (lx *lexer) word(c rune, at Pos) (token, *Refusal) {
	if identStart(c) {
		return token{kind: "ident", text: lx.takeWhile(identChar), at: at}, nil
	}
	if strings.ContainsRune("{}[](),:=.", c) {
		lx.bump()
		return token{kind: "punct", text: string(c), at: at}, nil
	}
	return token{}, refuse("syntax", at)
}

func (lx *lexer) str(at Pos) (string, *Refusal) {
	quote, _ := lx.bump()
	var b strings.Builder
	for {
		c, ok := lx.bump()
		switch {
		case !ok || lx.unwritten(c):
			return "", refuse("text", at)
		case c == quote:
			return b.String(), nil
		case c == '\\':
			e, ok := lx.escape(quote)
			if !ok {
				return "", refuse("text", at)
			}
			b.WriteRune(e)
		default:
			b.WriteRune(c)
		}
	}
}

// unwritten is what a quoted string may not hold raw: a line break or a
// tab, and `${`, a template literal's interpolation.
func (lx *lexer) unwritten(c rune) bool {
	return c == '\n' || c == '\r' || c == '\t' || lx.interpolation(c)
}

// interpolation is `${`, a template literal's, which the subset refuses.
func (lx *lexer) interpolation(c rune) bool {
	n, _ := lx.peek(0)
	return c == '$' && n == '{'
}
