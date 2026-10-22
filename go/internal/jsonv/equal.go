package jsonv

import (
	"encoding/json"
	"math/big"
)

// Equal is JSON equality: objects compare member by member whatever their
// order, numbers by value. The canvas vectors (conformance/vectors) are
// judged with it.
func Equal(a, b any) bool {
	switch x := normal(a).(type) {
	case *Object:
		y, ok := normal(b).(*Object)
		return ok && objectsEqual(x, y)
	case []any:
		y, ok := normal(b).([]any)
		return ok && arraysEqual(x, y)
	case json.Number:
		y, ok := normal(b).(json.Number)
		return ok && numbersEqual(x, y)
	default:
		return normal(a) == normal(b)
	}
}

// normal turns the Go-native spellings Marshal accepts into JSON's own.
func normal(v any) any {
	switch t := v.(type) {
	case []string:
		out := make([]any, len(t))
		for i, s := range t {
			out[i] = s
		}
		return out
	case int:
		return json.Number(Marshal(t))
	case int64:
		return json.Number(Marshal(t))
	}
	return v
}

func objectsEqual(x, y *Object) bool {
	if x.Len() != y.Len() {
		return false
	}
	for _, k := range x.Keys() {
		if !y.Has(k) || !Equal(x.Get(k), y.Get(k)) {
			return false
		}
	}
	return true
}

func arraysEqual(x, y []any) bool {
	if len(x) != len(y) {
		return false
	}
	for i := range x {
		if !Equal(x[i], y[i]) {
			return false
		}
	}
	return true
}

func numbersEqual(x, y json.Number) bool {
	a, okA := new(big.Float).SetString(string(x))
	b, okB := new(big.Float).SetString(string(y))
	return okA && okB && a.Cmp(b) == 0
}
