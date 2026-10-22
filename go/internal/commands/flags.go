package commands

import (
	"fmt"
	"strconv"
	"strings"

	"github.com/spf13/cobra"
	"github.com/spf13/pflag"

	"getlingara.com/cli/internal/core"
)

// enumValue is a string flag held to the table's values, so a bad value is
// a parse error (exit usage) that reaches no server. repeatable makes it a
// stringArray, which is how __tree tells a repeatable flag.
type enumValue struct {
	values     []string
	repeatable bool
	set        []string
}

func (e *enumValue) String() string { return strings.Join(e.set, ",") }

func (e *enumValue) Set(v string) error {
	for _, allowed := range e.values {
		if v == allowed {
			if e.repeatable {
				e.set = append(e.set, v)
			} else {
				e.set = []string{v}
			}
			return nil
		}
	}
	return fmt.Errorf("%q is not one of %s", v, strings.Join(e.values, ", "))
}

func (e *enumValue) Type() string {
	if e.repeatable {
		return "stringArray"
	}
	return "string"
}

// portValue is an int flag from 1 to 65535.
type portValue struct{ n int }

func (p *portValue) String() string { return strconv.Itoa(p.n) }

func (p *portValue) Set(v string) error {
	n, err := strconv.Atoi(v)
	if err != nil || n < 1 || n > 65535 {
		return fmt.Errorf("%q is not a port from 1 to 65535", v)
	}
	p.n = n
	return nil
}

func (p *portValue) Type() string { return "int" }

// flags declares a leaf's flags on cobra, consulting the kit-local table
// for the ones pflag cannot type.
type flags struct {
	cmd  *cobra.Command
	spec leafSpec
}

func (f flags) fs() *pflag.FlagSet { return f.cmd.Flags() }

// str is a string flag, or an enum where the table says so.
func (f flags) str(name, usage string) flags {
	if t, ok := f.spec.types[name]; ok && t.kind == "enum" {
		f.fs().Var(&enumValue{values: t.values}, name, usage)
		return f
	}
	f.fs().String(name, "", usage)
	return f
}

// strs is a repeatable string flag, or a repeatable enum.
func (f flags) strs(name, usage string) flags {
	if t, ok := f.spec.types[name]; ok && t.kind == "enum" {
		f.fs().Var(&enumValue{values: t.values, repeatable: true}, name, usage)
		return f
	}
	f.fs().StringArray(name, nil, usage)
	return f
}

func (f flags) boolean(name, usage string) flags {
	f.fs().Bool(name, false, usage)
	return f
}

func (f flags) port(name, usage string) flags {
	f.fs().Var(&portValue{}, name, usage)
	return f
}

// need marks the last-declared flags required.
func (f flags) need(names ...string) flags {
	for _, n := range names {
		_ = f.cmd.MarkFlagRequired(n)
	}
	return f
}

// conflicts is each pair the schema's conflicts names.
func (f flags) conflicts(pairs ...[2]string) flags {
	for _, p := range pairs {
		f.cmd.MarkFlagsMutuallyExclusive(p[0], p[1])
	}
	return f
}

// confirm is the confirm flag group every dangerous command carries
// (16.10.26ad D5).
func (f flags) confirm() flags {
	f.fs().BoolP("yes", "y", false, "Answer the y/n question; needs --confirm too")
	f.fs().String("confirm", "", "Your developer email, typed back; needs -y too")
	return f
}

// input is a leaf's parsed argv, as its handler reads it.
type input struct {
	cmd  *cobra.Command
	args []string
}

func (in input) arg(i int) string {
	if i < len(in.args) {
		return in.args[i]
	}
	return ""
}

func (in input) str(name string) string {
	f := in.cmd.Flags().Lookup(name)
	if f == nil {
		return ""
	}
	if e, ok := f.Value.(*enumValue); ok {
		return e.String()
	}
	return f.Value.String()
}

// opt is a flag's value when it was given, nil when it was not.
func (in input) opt(name string) *string {
	if !in.cmd.Flags().Changed(name) {
		return nil
	}
	v := in.str(name)
	return &v
}

func (in input) list(name string) []string {
	f := in.cmd.Flags().Lookup(name)
	if f == nil {
		return nil
	}
	switch v := f.Value.(type) {
	case *enumValue:
		return v.set
	case pflag.SliceValue:
		return v.GetSlice()
	}
	return nil
}

func (in input) flag(name string) bool {
	v, _ := in.cmd.Flags().GetBool(name)
	return v
}

func (in input) port(name string) int {
	if p, ok := in.cmd.Flags().Lookup(name).Value.(*portValue); ok {
		return p.n
	}
	return 0
}

func (in input) confirmFlags() core.ConfirmFlags {
	return core.ConfirmFlags{Yes: in.flag("yes"), Confirm: in.opt("confirm")}
}
