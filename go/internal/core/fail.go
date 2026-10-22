package core

import "getlingara.com/cli/internal/assets"

// Made is an object a create made before it failed, named on stderr (D2).
type Made struct {
	Kind string
	ID   string
}

// Fail is why a command stopped: its exit, the code and text B2 prints, any
// extra lines of advice, and what a part-way create already made. Every
// handler returns a *Fail, nil meaning it succeeded, so no command can
// invent its own refusal shape.
type Fail struct {
	Exit    Exit
	Code    string
	Message string
	Hints   []string
	Made    []Made
	// Body is the refusing answer's body, for a command that re-words it
	// (apps dev's dev_session_held reads the lease from it).
	Body string
}

// NewFail is a refusal with a code and text.
func NewFail(exit Exit, code, message string) *Fail {
	return &Fail{Exit: exit, Code: code, Message: message}
}

// Hint adds one line of advice and returns the same refusal.
func (f *Fail) Hint(line string) *Fail {
	f.Hints = append(f.Hints, line)
	return f
}

// Usage is exit 2: a bad argv, refused before any request.
func Usage(message string) *Fail {
	return NewFail(ExitUsage, "usage", message)
}

// Auth is exit 3: no usable credentials.
func Auth(message string) *Fail {
	return NewFail(ExitAuthRequired, "auth_required", message)
}

// Failure is exit 1: a bug, or something the kit did not expect.
func Failure(message string) *Fail {
	return NewFail(ExitFailure, "failure", message)
}

// Message is a refusal with a messages.en.json text: code and message id are
// the same word, as every 16.10.26ae row's are.
func Message(exit Exit, id string, vars ...string) *Fail {
	return NewFail(exit, id, assets.Message(id, vars...))
}

// Interrupted is exit 130, returned after cleanup, never death by signal
// (ADR 16.10.26af D9).
func Interrupted() *Fail {
	return NewFail(ExitInterrupted, "interrupted", "interrupted")
}
