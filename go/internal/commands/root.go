// Package commands is the cobra command tree (ADR 16.10.26af D2: the de
// facto Go CLI framework), its handlers' glue and the hidden __tree. Each
// topic registers its own leaves; Run is the one entry point, used by main
// and, in-process, by canvas apply's inner verbs (16.10.26ad D7 step 4).
//
// D9's overrides live here, in one place: SilenceUsage and SilenceErrors;
// every group takes no args and answers a bare call with a usage error
// rather than help and exit 0; every leaf's arity comes from the kit-local
// table; and every flag or argument error is exit usage (2), not cobra's 1.
package commands

import (
	"bytes"
	"errors"
	"io"
	"strings"

	"github.com/spf13/cobra"

	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/core/store"
)

// Version is the repository's VERSION, set at build time by -ldflags
// (make/go.mk and .goreleaser.yaml), never read from a file at run time.
var Version = "0.0.0-dev"

// Kit is this kit's languages.toml id.
const Kit = "go"

type handler func(c *app.Ctx, in input) *core.Fail

// failed carries a handler's refusal out through cobra's RunE, which only
// speaks error; Run unwraps it, so it is never printed as cobra's own.
type failed struct{ fail *core.Fail }

func (f failed) Error() string { return f.fail.Message }

// builder holds one run: the app, where stderr goes, and the leaf's output.
type builder struct {
	app    *app.App
	stderr io.Writer
	out    *core.Output
}

// Run parses argv (without the binary's name) and runs the command,
// writing stderr to stderr and buffering stdout into the outcome.
func Run(a *app.App, argv []string, stderr io.Writer) app.Outcome {
	b := &builder{app: a, stderr: stderr}
	root := b.root()
	var printed bytes.Buffer
	root.SetArgs(argv)
	root.SetOut(&printed)
	root.SetErr(stderr)
	err := root.Execute()
	var f failed
	switch {
	case err == nil:
		return app.Outcome{Exit: core.ExitOk, Stdout: append(printed.Bytes(), b.stdout()...)}
	case errors.As(err, &f):
		return b.finish(f.fail)
	}
	jsonFlag := false
	for _, a := range argv {
		jsonFlag = jsonFlag || a == "--json"
	}
	b.out = core.NewOutput(stderr, jsonFlag)
	usage := core.Usage(err.Error())
	if !jsonFlag {
		usage.Hint("Run 'lingara --help' for usage.")
	}
	return b.finish(usage)
}

func (b *builder) stdout() []byte {
	if b.out == nil {
		return nil
	}
	return b.out.Stdout.Bytes()
}

func (b *builder) finish(fail *core.Fail) app.Outcome {
	if b.out == nil {
		b.out = core.NewOutput(b.stderr, false)
	}
	for _, line := range core.ErrorLines(fail, b.out.JSON) {
		b.out.Note(line)
	}
	return app.Outcome{Exit: fail.Exit, Stdout: b.stdout(), Fail: fail}
}

func (b *builder) root() *cobra.Command {
	root := &cobra.Command{
		Use:           "lingara",
		Short:         "lingara, the Lingara developer CLI",
		Version:       Version + " (" + Kit + ")",
		SilenceUsage:  true,
		SilenceErrors: true,
		Args:          cobra.NoArgs,
		RunE:          missingSubcommand,
	}
	root.SetVersionTemplate("lingara {{.Version}}\n")
	root.CompletionOptions.DisableDefaultCmd = true
	root.SetFlagErrorFunc(func(_ *cobra.Command, err error) error { return err })
	root.PersistentFlags().Bool("json", false, "Machine-readable output on stdout")
	root.PersistentFlags().String("profile", "", "The stored credentials to use")
	b.session(root)
	b.apps(root)
	b.embed(root)
	b.oauthClient(root)
	b.webhook(root)
	b.canvas(root)
	b.leaf(root, "__tree", "The registered command tree as JSON, for make check-tree", printTree).cmd.Hidden = true
	return root
}

func missingSubcommand(cmd *cobra.Command, _ []string) error {
	return errors.New("'" + cmd.CommandPath() + "' requires a subcommand but one was not provided")
}

// group is a topic: no args, and a bare call is a usage error.
func (b *builder) group(parent *cobra.Command, name, short string, aliases ...string) *cobra.Command {
	cmd := &cobra.Command{Use: name, Short: short, Aliases: aliases, Args: cobra.NoArgs, RunE: missingSubcommand}
	parent.AddCommand(cmd)
	return cmd
}

// leafPath is the canonical path under the root: the kit-local table's key.
func leafPath(parent *cobra.Command, name string) string {
	path := strings.TrimPrefix(parent.CommandPath()+" "+name, "lingara ")
	return strings.TrimPrefix(path, "lingara")
}

// leaf is a command whose arity is the table's and whose RunE opens the
// run's context and calls h.
func (b *builder) leaf(parent *cobra.Command, name, short string, h handler) flags {
	spec := kitTable[leafPath(parent, name)]
	use := name
	min := 0
	for _, a := range spec.args {
		if a.required {
			use += " <" + a.name + ">"
			min++
		} else {
			use += " [" + a.name + "]"
		}
	}
	cmd := &cobra.Command{Use: use, Short: short, Args: cobra.RangeArgs(min, len(spec.args))}
	cmd.RunE = func(cmd *cobra.Command, args []string) error {
		if fail := h(b.context(cmd), input{cmd: cmd, args: args}); fail != nil {
			return failed{fail}
		}
		return nil
	}
	parent.AddCommand(cmd)
	return flags{cmd: cmd, spec: spec}
}

// context is the run's Ctx: the profile (--profile, then LINGARA_PROFILE,
// then default), its config.toml settings, the API base and the streams.
func (b *builder) context(cmd *cobra.Command) *app.Ctx {
	globals := cmd.Root().PersistentFlags()
	profile, _ := globals.GetString("profile")
	chosen := globals.Changed("profile")
	if !chosen {
		profile, chosen = b.app.Env.Get("LINGARA_PROFILE")
	}
	if !chosen {
		profile = "default"
	}
	config := store.LoadProfileConfig(b.app.ConfigDir, profile)
	b.app.HTTP.API = b.apiBase(config.APIURL)
	jsonFlag, _ := globals.GetBool("json")
	b.out = core.NewOutput(b.stderr, jsonFlag || config.Output == "json")
	return &app.Ctx{App: b.app, Out: b.out, Profile: profile, ProfileChosen: chosen}
}

// apiBase is D3's order: LINGARA_API_URL, then platform.toml's [platform]
// api (applied by the apps commands that open one), then the profile's
// api_url, then endpoints.json's.
func (b *builder) apiBase(profileAPI string) string {
	if v, ok := b.app.Env.Get("LINGARA_API_URL"); ok {
		return v
	}
	if profileAPI != "" {
		return profileAPI
	}
	return b.app.Endpoints.APIURL
}
