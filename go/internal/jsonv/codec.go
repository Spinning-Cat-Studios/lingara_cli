package jsonv

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sort"
	"strconv"
	"strings"
	"unicode/utf8"
)

// Parse reads one JSON document, numbers kept as written.
func Parse(data []byte) (any, error) {
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.UseNumber()
	v, err := decode(dec)
	if err != nil {
		return nil, err
	}
	if _, err := dec.Token(); !errors.Is(err, io.EOF) {
		return nil, errors.New("trailing data after the JSON value")
	}
	return v, nil
}

func decode(dec *json.Decoder) (any, error) {
	tok, err := dec.Token()
	if err != nil {
		return nil, err
	}
	delim, ok := tok.(json.Delim)
	if !ok {
		return tok, nil
	}
	if delim == '[' {
		return decodeArray(dec)
	}
	return decodeObject(dec)
}

func decodeArray(dec *json.Decoder) (any, error) {
	out := []any{}
	for dec.More() {
		v, err := decode(dec)
		if err != nil {
			return nil, err
		}
		out = append(out, v)
	}
	_, err := dec.Token()
	return out, err
}

func decodeObject(dec *json.Decoder) (any, error) {
	out := NewObject()
	for dec.More() {
		tok, err := dec.Token()
		if err != nil {
			return nil, err
		}
		key, _ := tok.(string)
		v, err := decode(dec)
		if err != nil {
			return nil, err
		}
		out.Set(key, v)
	}
	_, err := dec.Token()
	return out, err
}

// Marshal is compact JSON, written as serde_json writes it: no spaces, no
// HTML escaping, members in order.
func Marshal(v any) string {
	var b strings.Builder
	write(&b, v)
	return b.String()
}

func write(b *strings.Builder, v any) {
	switch t := v.(type) {
	case nil:
		b.WriteString("null")
	case bool:
		b.WriteString(strconv.FormatBool(t))
	case string:
		writeString(b, t)
	case json.Number:
		b.WriteString(string(t))
	case int:
		b.WriteString(strconv.Itoa(t))
	case int64:
		b.WriteString(strconv.FormatInt(t, 10))
	case uint16:
		b.WriteString(strconv.Itoa(int(t)))
	case *Object:
		writeObject(b, t)
	default:
		writeOther(b, v)
	}
}

func writeOther(b *strings.Builder, v any) {
	switch t := v.(type) {
	case []any:
		writeArray(b, t)
	case []string:
		items := make([]any, len(t))
		for i, s := range t {
			items[i] = s
		}
		writeArray(b, items)
	case map[string]any:
		writeObject(b, sortedObject(t))
	default:
		fmt.Fprintf(b, "%v", t)
	}
}

func sortedObject(m map[string]any) *Object {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	o := NewObject()
	for _, k := range keys {
		o.Set(k, m[k])
	}
	return o
}

func writeArray(b *strings.Builder, items []any) {
	b.WriteByte('[')
	for i, item := range items {
		if i > 0 {
			b.WriteByte(',')
		}
		write(b, item)
	}
	b.WriteByte(']')
}

func writeObject(b *strings.Builder, o *Object) {
	b.WriteByte('{')
	for i, k := range o.Keys() {
		if i > 0 {
			b.WriteByte(',')
		}
		writeString(b, k)
		b.WriteByte(':')
		write(b, o.Get(k))
	}
	b.WriteByte('}')
}

const hexDigits = "0123456789abcdef"

// writeString escapes only what JSON requires, with serde_json's spellings.
func writeString(b *strings.Builder, s string) {
	b.WriteByte('"')
	for i := 0; i < len(s); {
		r, size := utf8.DecodeRuneInString(s[i:])
		i += size
		writeRune(b, r)
	}
	b.WriteByte('"')
}

func writeRune(b *strings.Builder, r rune) {
	switch r {
	case '"':
		b.WriteString(`\"`)
	case '\\':
		b.WriteString(`\\`)
	case '\n':
		b.WriteString(`\n`)
	case '\r':
		b.WriteString(`\r`)
	case '\t':
		b.WriteString(`\t`)
	case '\b':
		b.WriteString(`\b`)
	case '\f':
		b.WriteString(`\f`)
	default:
		if r < 0x20 {
			b.WriteString(`\u00`)
			b.WriteByte(hexDigits[r>>4])
			b.WriteByte(hexDigits[r&0xf])
			return
		}
		b.WriteRune(r)
	}
}
