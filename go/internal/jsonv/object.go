// Package jsonv is the JSON value every command reads and prints: an object
// that keeps its members in the order they arrived, because contract B1
// prints the server's object "as the server answered it", and a get's
// FIELD/VALUE table lists the members in that order too. encoding/json's
// map[string]any would sort them, which no other kit does (ADR 16.10.26af
// D1).
//
// A value is nil (null), bool, string, json.Number, []any or *Object. Go
// slices and integers are accepted when marshalling, so a body can be
// written as jsonv.Obj("name", name, "scopes", scopes).
package jsonv

// Object is a JSON object whose members keep their insertion order. Setting
// a member that exists replaces it in place, as serde_json's preserve_order
// map does in the Rust kit.
type Object struct {
	keys []string
	vals map[string]any
}

// NewObject is an empty object.
func NewObject() *Object {
	return &Object{vals: map[string]any{}}
}

// Obj builds an object from key, value pairs, in order: the jsonv spelling
// of serde_json's json! literal.
func Obj(pairs ...any) *Object {
	o := NewObject()
	for i := 0; i+1 < len(pairs); i += 2 {
		key, _ := pairs[i].(string)
		o.Set(key, pairs[i+1])
	}
	return o
}

// Get is the member's value, nil when absent.
func (o *Object) Get(key string) any {
	if o == nil {
		return nil
	}
	return o.vals[key]
}

// Has reports whether the member is present, even as null.
func (o *Object) Has(key string) bool {
	if o == nil {
		return false
	}
	_, ok := o.vals[key]
	return ok
}

// Set replaces the member in place, or appends it.
func (o *Object) Set(key string, value any) {
	if _, ok := o.vals[key]; !ok {
		o.keys = append(o.keys, key)
	}
	o.vals[key] = value
}

// Keys are the member names, in order.
func (o *Object) Keys() []string {
	if o == nil {
		return nil
	}
	return append([]string(nil), o.keys...)
}

// Len is the number of members.
func (o *Object) Len() int {
	if o == nil {
		return 0
	}
	return len(o.keys)
}

// Clone is a deep copy, so an overlay never edits the value it was read from.
func Clone(v any) any {
	switch t := v.(type) {
	case *Object:
		out := NewObject()
		for _, k := range t.keys {
			out.Set(k, Clone(t.vals[k]))
		}
		return out
	case []any:
		out := make([]any, len(t))
		for i, item := range t {
			out[i] = Clone(item)
		}
		return out
	default:
		return v
	}
}
