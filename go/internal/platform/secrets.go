package platform

import (
	"strings"

	"github.com/pelletier/go-toml/v2/unstable"
)

// D4's platform_toml_secret: the first value or key, in file order, that
// looks like a secret. A value is one when it matches
// `lgr_[a-z]+_[A-Za-z0-9]{8,}` and is not a public client id (lgr_cid_…); a
// key is one when its _-separated words include secret, token or password,
// case-insensitively (CLIENT_SECRET, api_token; never TOKENIZER). The
// refusal names the key, never the value.
//
// go-toml's maps forget the file's order, so the walk reads its expressions
// in order through the parser, which every other kit's TOML library keeps.

var secretWords = []string{"secret", "token", "password"}

// SecretValue reports whether s holds a real-looking lgr_ token other than
// a client id.
func SecretValue(s string) bool {
	rest := s
	for {
		at := strings.Index(rest, "lgr_")
		if at < 0 {
			return false
		}
		tail := rest[at+4:]
		kind := leading(tail, func(c byte) bool { return c >= 'a' && c <= 'z' })
		if kind > 0 && strings.HasPrefix(tail[kind:], "_") {
			body := leading(tail[kind+1:], isAlnum)
			if body >= 8 && tail[:kind] != "cid" {
				return true
			}
		}
		rest = tail
	}
}

func leading(s string, keep func(byte) bool) int {
	n := 0
	for n < len(s) && keep(s[n]) {
		n++
	}
	return n
}

func isAlnum(c byte) bool {
	return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
}

// SecretKey reports whether one of key's _-separated words is a secret word.
func SecretKey(key string) bool {
	for _, word := range strings.Split(key, "_") {
		for _, s := range secretWords {
			if strings.EqualFold(word, s) {
				return true
			}
		}
	}
	return false
}

// FirstSecret is the dotted path of the first secret-shaped key or value.
func FirstSecret(data []byte) (string, bool) {
	p := unstable.Parser{}
	p.Reset(data)
	var table []string
	for p.NextExpression() {
		e := p.Expression()
		switch e.Kind {
		case unstable.Table, unstable.ArrayTable:
			table = nil
			if path, found := walkKey(e.Key(), &table); found {
				return path, true
			}
		case unstable.KeyValue:
			path := append([]string(nil), table...)
			if found, ok := walkKey(e.Key(), &path); ok {
				return found, true
			}
			if found, ok := walkValue(e.Value(), path); ok {
				return found, true
			}
		}
	}
	return "", false
}

// walkKey appends each part of a dotted key to path, stopping at the first
// secret-shaped one.
func walkKey(it unstable.Iterator, path *[]string) (string, bool) {
	for it.Next() {
		part := string(it.Node().Data)
		*path = append(*path, part)
		if SecretKey(part) {
			return strings.Join(*path, "."), true
		}
	}
	return "", false
}

func walkValue(n *unstable.Node, path []string) (string, bool) {
	switch n.Kind {
	case unstable.String:
		if SecretValue(string(n.Data)) {
			return strings.Join(path, "."), true
		}
	case unstable.Array:
		it := n.Children()
		for it.Next() {
			if found, ok := walkValue(it.Node(), path); ok {
				return found, true
			}
		}
	case unstable.InlineTable:
		it := n.Children()
		for it.Next() {
			kv := it.Node()
			inner := append([]string(nil), path...)
			if found, ok := walkKey(kv.Key(), &inner); ok {
				return found, true
			}
			if found, ok := walkValue(kv.Value(), inner); ok {
				return found, true
			}
		}
	}
	return "", false
}
