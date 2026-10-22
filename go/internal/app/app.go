// Package app is one run's state: the HTTP client, the environment, the
// credential stores, the terminal and the signed-in session, and the
// session's authenticated calls (16.10.26ad D3; contract B2 rule 2, B6).
// Every command handler takes a *Ctx.
package app

import (
	"io"

	"getlingara.com/cli/internal/assets"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/core/store"
	"getlingara.com/cli/internal/httpx"
)

// Terminal is the terminal the ritual talks to (16.10.26ad D5 step 1).
type Terminal interface {
	// Interactive is stdin and stderr both terminals.
	Interactive() bool
	// ReadLine is one line from stdin; false at end of input.
	ReadLine() (string, bool)
}

// Outcome is what a run ends with: the exit, the buffered stdout, and the
// refusal, which canvas apply's in-process verbs hand back.
type Outcome struct {
	Exit   core.Exit
	Stdout []byte
	Fail   *core.Fail
}

// App is the process-wide state. Dispatch is the command tree's own entry
// point, set by package commands, which canvas apply calls in-process (D7
// step 4) so its inner verbs share this App's signed-in session and no
// second token exchange happens.
type App struct {
	HTTP      *httpx.HTTP
	Env       core.Env
	Stores    *store.Stores
	Terminal  Terminal
	Browser   func(url string) bool
	Endpoints assets.Endpoints
	ConfigDir string
	Session   *Session
	Dispatch  func(argv []string, stderr io.Writer) Outcome
}

// Ctx is what one command sees: the app, its streams and the profile.
type Ctx struct {
	App     *App
	Out     *core.Output
	Profile string
	// ProfileChosen is --profile or LINGARA_PROFILE choosing it, so
	// platform.toml's [platform] profile may not (16.10.26ae D4).
	ProfileChosen bool
}

// CIPair is the CI credentials, when both halves are set (D3 rule 1).
func (c *Ctx) CIPair() (string, string, bool) {
	id, okID := c.App.Env.Get("LINGARA_PLATFORM_CLIENT_ID")
	secret, okSecret := c.App.Env.Get("LINGARA_PLATFORM_CLIENT_SECRET")
	return id, secret, okID && okSecret
}

// Interactive is the ritual's terminal question.
func (c *Ctx) Interactive() bool { return c.App.Terminal.Interactive() }

// ReadLine is one line of the developer's answer.
func (c *Ctx) ReadLine() (string, bool) { return c.App.Terminal.ReadLine() }

// Prompt writes a prompt to stderr.
func (c *Ctx) Prompt(text string) { c.Out.Prompt(text) }
