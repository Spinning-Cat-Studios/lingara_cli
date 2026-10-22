package commands

import (
	"github.com/spf13/cobra"
	"github.com/spf13/pflag"

	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/jsonv"
)

// lingara __tree (ADR 16.10.26ab D4; 16.10.26af D2): the registered tree,
// read back from cobra's own model — commands, aliases, flags, their pflag
// types and whether they are required or repeatable — with positional args
// and the path and enum types from the kit-local table, the same table
// registration read. A command is dangerous exactly when it carries the
// confirm group, which is the schema's own rule (16.10.26ab D2.4).

func printTree(c *app.Ctx, in input) *core.Fail {
	c.Out.JSONValue(Tree(in.cmd.Root()))
	return nil
}

// Tree is the projection of root.
func Tree(root *cobra.Command) *jsonv.Object {
	out := level(root)
	out.Set("name", root.Name())
	var globals []any
	root.PersistentFlags().VisitAll(func(f *pflag.Flag) {
		globals = append(globals, param(f, leafSpec{}))
	})
	out.Set("global_flags", globals)
	return out
}

// level is a topic's commands and sub-topics: a command with commands of
// its own is a topic.
func level(cmd *cobra.Command) *jsonv.Object {
	topics, commands := []any{}, []any{}
	for _, sub := range cmd.Commands() {
		if !sub.IsAvailableCommand() {
			continue
		}
		if sub.HasSubCommands() {
			t := level(sub)
			t.Set("name", sub.Name())
			t.Set("aliases", aliases(sub))
			topics = append(topics, t)
		} else {
			commands = append(commands, command(sub))
		}
	}
	return jsonv.Obj("commands", commands, "topics", topics)
}

func aliases(cmd *cobra.Command) []string {
	return append([]string{}, cmd.Aliases...)
}

func command(cmd *cobra.Command) *jsonv.Object {
	spec := kitTable[leafPath(cmd.Parent(), cmd.Name())]
	flags := []any{}
	cmd.LocalNonPersistentFlags().VisitAll(func(f *pflag.Flag) {
		if f.Name != "help" {
			flags = append(flags, param(f, spec))
		}
	})
	args := make([]any, len(spec.args))
	for i, a := range spec.args {
		args[i] = jsonv.Obj("name", a.name, "type", "string", "required", a.required, "repeatable", false)
	}
	dangerous := cmd.LocalNonPersistentFlags().Lookup("confirm") != nil
	return jsonv.Obj("name", cmd.Name(), "aliases", aliases(cmd), "dangerous", dangerous, "args", args, "flags", flags)
}

// param is one flag: pflag's type, refined by the table where pflag is
// coarser.
func param(f *pflag.Flag, spec leafSpec) *jsonv.Object {
	kind, repeatable := "string", false
	switch f.Value.Type() {
	case "bool":
		kind = "bool"
	case "int":
		kind = "int"
	case "stringArray":
		repeatable = true
	}
	t, typed := spec.types[f.Name]
	if typed {
		kind = t.kind
	}
	_, required := f.Annotations[cobra.BashCompOneRequiredFlag]
	out := jsonv.Obj("name", f.Name, "type", kind, "required", required, "repeatable", repeatable)
	if f.Shorthand != "" {
		out.Set("short", f.Shorthand)
	}
	if typed && t.kind == "enum" {
		out.Set("values", t.values)
	}
	return out
}
