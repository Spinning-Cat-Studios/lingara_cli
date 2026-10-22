// Command lingara is the Lingara developer CLI: the Go kit (ADR
// 16.10.26af). Every behaviour a developer or CI job can observe is
// conformance/CONTRACT.md's; this module is one way of keeping it.
package main

import (
	"io"
	"os"

	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/commands"
	"getlingara.com/cli/internal/core"
)

func main() {
	os.Exit(run(os.Args[1:]))
}

// run is one process: Ctrl+C trapped (D9), the production App built, argv
// dispatched, stdout written once, the exit returned.
func run(argv []string) int {
	core.WatchSignals(func() {
		// Nothing has claimed the signal, so nothing is mid-cleanup: end
		// with exit 130, as every kit does, rather than dying by signal.
		_, _ = os.Stderr.WriteString("error: interrupted (interrupted)\n")
		os.Exit(int(core.ExitInterrupted))
	})
	a := app.Production(core.ProcessEnv(), commands.Version, commands.Kit)
	a.Dispatch = func(argv []string, stderr io.Writer) app.Outcome { return commands.Run(a, argv, stderr) }
	outcome := commands.Run(a, argv, os.Stderr)
	_, _ = os.Stdout.Write(outcome.Stdout)
	return int(outcome.Exit)
}
