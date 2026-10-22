package platform

import (
	"bytes"
	"errors"
	"fmt"
	"strings"
	"sync"

	"github.com/santhosh-tekuri/jsonschema/v6"
	"golang.org/x/text/language"
	"golang.org/x/text/message"

	"getlingara.com/cli/internal/assets"
)

// The rules spec/platform-toml.schema.json writes, checked by a JSON Schema
// engine over the parsed file (ADR 16.10.26af D2: santhosh-tekuri/
// jsonschema), so the kit and the schema cannot drift apart. The file's
// own copy is assets' platform-toml.schema.json.

const schemaURL = "https://getlingara.com/schemas/cli/platform-toml/1"

var (
	compiled    *jsonschema.Schema
	compileErr  error
	compileOnce sync.Once
)

func schema() (*jsonschema.Schema, error) {
	compileOnce.Do(func() {
		doc, err := jsonschema.UnmarshalJSON(bytes.NewReader(assets.Read("platform-toml.schema.json")))
		if err != nil {
			compileErr = err
			return
		}
		c := jsonschema.NewCompiler()
		if err := c.AddResource(schemaURL, doc); err != nil {
			compileErr = err
			return
		}
		compiled, compileErr = c.Compile(schemaURL)
	})
	return compiled, compileErr
}

// CheckSchema is "" when doc keeps every rule, else why not. The schema
// version is read first, so a file from a newer lingara says so rather than
// naming whichever key it added.
func CheckSchema(doc map[string]any) string {
	switch v := doc["schema"].(type) {
	case nil:
		return "schema = 1 is missing"
	case int64:
		if v != 1 {
			return fmt.Sprintf("schema is %d; this lingara reads 1", v)
		}
	default:
		return fmt.Sprintf("schema is %v; this lingara reads 1", v)
	}
	sch, err := schema()
	if err != nil {
		return "the kit's own schema did not compile: " + err.Error()
	}
	err = sch.Validate(jsonCompatible(doc))
	var ve *jsonschema.ValidationError
	if errors.As(err, &ve) {
		return reason(ve)
	}
	if err != nil {
		return err.Error()
	}
	return ""
}

// reason is the first leaf refusal, as `<pointer>: <what>`.
func reason(ve *jsonschema.ValidationError) string {
	for len(ve.Causes) > 0 {
		ve = ve.Causes[0]
	}
	at := strings.Join(ve.InstanceLocation, ".")
	what := ve.ErrorKind.LocalizedString(message.NewPrinter(language.English))
	if at == "" {
		return what
	}
	return at + ": " + what
}

// jsonCompatible turns TOML's dates and times into strings, the one TOML
// value a JSON Schema engine has no type for.
func jsonCompatible(v any) any {
	switch t := v.(type) {
	case map[string]any:
		out := make(map[string]any, len(t))
		for k, item := range t {
			out[k] = jsonCompatible(item)
		}
		return out
	case []any:
		out := make([]any, len(t))
		for i, item := range t {
			out[i] = jsonCompatible(item)
		}
		return out
	case string, bool, int64, float64, nil:
		return t
	default:
		return fmt.Sprint(t)
	}
}

// AppNameOK is a lowercase letter, then up to 39 lowercase letters, digits
// or hyphens: the schema's name pattern, needed before any file exists.
func AppNameOK(name string) bool {
	if name == "" || len(name) > 40 || name[0] < 'a' || name[0] > 'z' {
		return false
	}
	return strings.Trim(name, "abcdefghijklmnopqrstuvwxyz0123456789-") == ""
}
