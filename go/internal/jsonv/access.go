package jsonv

import (
	"encoding/json"
	"strconv"
)

// Get walks object members by key; nil wherever a step is missing or not an
// object, as serde_json's `value["a"]["b"]` is Null.
func Get(v any, path ...string) any {
	for _, key := range path {
		o, ok := v.(*Object)
		if !ok {
			return nil
		}
		v = o.Get(key)
	}
	return v
}

// Str is the value as a string, if it is one.
func Str(v any) (string, bool) {
	s, ok := v.(string)
	return s, ok
}

// Text is the string at path, or "" where there is none.
func Text(v any, path ...string) string {
	s, _ := Get(v, path...).(string)
	return s
}

// TextOr is the string at path, or fallback where there is none.
func TextOr(v any, fallback string, path ...string) string {
	if s, ok := Get(v, path...).(string); ok {
		return s
	}
	return fallback
}

// Arr is the value as an array; nil for anything else. A []string a body
// was built with reads as the array it marshals to.
func Arr(v any) []any {
	switch t := v.(type) {
	case []any:
		return t
	case []string:
		out := make([]any, len(t))
		for i, s := range t {
			out[i] = s
		}
		return out
	}
	return nil
}

// AsObject is the value as an object; nil for anything else.
func AsObject(v any) *Object {
	o, _ := v.(*Object)
	return o
}

// Int is the value as a whole number that fits an int64.
func Int(v any) (int64, bool) {
	switch t := v.(type) {
	case json.Number:
		n, err := strconv.ParseInt(string(t), 10, 64)
		return n, err == nil
	case int:
		return int64(t), true
	case int64:
		return t, true
	}
	return 0, false
}

// Uint is the value as a non-negative whole number.
func Uint(v any) (uint64, bool) {
	n, ok := Int(v)
	if !ok || n < 0 {
		return 0, false
	}
	return uint64(n), true
}

// Bool is the value as a boolean, if it is one.
func Bool(v any) (bool, bool) {
	b, ok := v.(bool)
	return b, ok
}

// Strings is an array's string items; non-strings are skipped.
func Strings(v any) []string {
	out := []string{}
	for _, item := range Arr(v) {
		if s, ok := item.(string); ok {
			out = append(out, s)
		}
	}
	return out
}

// IsObject reports whether v is an object.
func IsObject(v any) bool {
	_, ok := v.(*Object)
	return ok
}

// IsArray reports whether v is an array.
func IsArray(v any) bool {
	switch v.(type) {
	case []any, []string:
		return true
	}
	return false
}
