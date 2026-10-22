package literal

import (
	"encoding/json"
	"math"
	"strconv"
	"strings"
	"unicode/utf8"
)

// The two scans that read more than one character's worth of rule: a
// string's escapes and a number's grammar.

var escapes = map[rune]rune{'"': '"', '\\': '\\', '/': '/', 'b': '\b', 'f': '\f', 'n': '\n', 'r': '\r', 't': '\t'}

// escape is JSON's escapes, and \' inside single quotes.
func (lx *lexer) escape(quote rune) (rune, bool) {
	c, ok := lx.bump()
	if !ok {
		return 0, false
	}
	if e, known := escapes[c]; known {
		return e, true
	}
	if c == '\'' && quote == '\'' {
		return '\'', true
	}
	if c != 'u' {
		return 0, false
	}
	var hex strings.Builder
	for i := 0; i < 4; i++ {
		if h, ok := lx.bump(); ok {
			hex.WriteRune(h)
		}
	}
	n, err := strconv.ParseUint(hex.String(), 16, 32)
	if err != nil || !utf8.ValidRune(rune(n)) {
		return 0, false
	}
	return rune(n), true
}

// number is a finite JSON number: -?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?.
func (lx *lexer) number(at Pos) (string, *Refusal) {
	text := lx.takeWhile(numberChar)
	if !wellFormed(text) {
		return "", refuse("number", at)
	}
	return text, nil
}

// numberChar is what the scan takes as one number: letters and digits, so
// `1e5x` is one bad token rather than a number and a name.
func numberChar(c rune) bool {
	return c < 128 && (isDigit(c) || c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || strings.ContainsRune("-+.", c))
}

func isDigit(c rune) bool { return c >= '0' && c <= '9' }

// wellFormed is JSON's number grammar, with no leading zero, and finite.
func wellFormed(text string) bool {
	digits := []rune(strings.TrimPrefix(text, "-"))
	if len(digits) == 0 || !isDigit(digits[0]) || len(digits) > 1 && digits[0] == '0' && isDigit(digits[1]) {
		return false
	}
	f, err := strconv.ParseFloat(text, 64)
	return json.Valid([]byte(text)) && err == nil && !math.IsInf(f, 0) && !math.IsNaN(f)
}
