package core

import (
	"strconv"
	"strings"
	"unicode/utf8"

	"getlingara.com/cli/internal/assets"
)

// The delete that asks twice (ADR 16.10.26ad D5; contract B3, invariant
// I1). One function, called by every dangerous command, so no command can
// carry a weaker copy.
//
//   - `-y --confirm <email>` replaces both prompts, interactive or not, and
//     sends no read for a prompt; the email is checked before anything is
//     destroyed. Neither flag alone is confirmation.
//   - Otherwise a non-interactive run is refused before any request, the
//     read and any token exchange included.
//   - Interactive: the command's read fills the prompt, y/yes continues,
//     then the typed email must match. There is no retry loop.

// Prompt is what the y/n question names: the object, its cascade, its pins.
type Prompt struct {
	Kind string
	Name string
	ID   string
	// Consequence is the kind's consequence_* line, or consequence_version.
	Consequence string
	PinnedBy    uint64
}

// ConfirmFlags is the confirm flag group every dangerous command carries.
// Neither flag reads the environment or configuration.
type ConfirmFlags struct {
	Yes     bool
	Confirm *string
}

// Ritual is what the ritual talks to: the terminal (interactive means stdin
// and stderr are terminals, B3.1), stderr for prompts, and the signed-in
// developer's email.
type Ritual interface {
	Interactive() bool
	// ReadLine is one line from stdin; false at end of input.
	ReadLine() (string, bool)
	Prompt(text string)
	Email() (string, *Fail)
}

// Canonical is trimmed and ASCII-lowercased: the comparison's rule and the
// form the destructive request carries.
func Canonical(email string) string {
	return asciiLower(strings.TrimSpace(email))
}

func asciiLower(s string) string {
	b := []byte(s)
	for i, c := range b {
		if c >= 'A' && c <= 'Z' {
			b[i] = c + ('a' - 'A')
		}
	}
	return string(b)
}

// EmailHint is `d…@example.com` for dev@example.com; a one-character local
// part is `…@example.com`. A typo stays catchable without printing the
// address.
func EmailHint(email string) string {
	local, domain, _ := strings.Cut(email, "@")
	first := ""
	if utf8.RuneCountInString(local) > 1 {
		r, _ := utf8.DecodeRuneInString(local)
		first = string(r)
	}
	return first + "…@" + domain
}

func notConfirmed(id string) *Fail {
	return NewFail(ExitNotConfirmed, id, assets.Message(id))
}

// ConfirmDestructive runs the ritual; the canonical email the destructive
// request then carries.
func ConfirmDestructive(r Ritual, flags ConfirmFlags, read func() (Prompt, *Fail)) (string, *Fail) {
	if flags.Yes && flags.Confirm != nil {
		return matchEmail(r, *flags.Confirm)
	}
	if !r.Interactive() {
		return "", notConfirmed("confirm_non_tty_refused")
	}
	prompt, fail := read()
	if fail != nil {
		return "", fail
	}
	r.Prompt(question(prompt) + " [y/N] ")
	answer, _ := r.ReadLine()
	if a := asciiLower(strings.TrimSpace(answer)); a != "y" && a != "yes" {
		return "", notConfirmed("confirm_declined")
	}
	email, fail := r.Email()
	if fail != nil {
		return "", fail
	}
	r.Prompt(assets.Message("confirm_type_email", "hint", EmailHint(Canonical(email))) + " ")
	typed, _ := r.ReadLine()
	return compare(typed, email)
}

func matchEmail(r Ritual, typed string) (string, *Fail) {
	email, fail := r.Email()
	if fail != nil {
		return "", fail
	}
	return compare(typed, email)
}

func compare(typed, email string) (string, *Fail) {
	if Canonical(typed) != Canonical(email) {
		return "", notConfirmed("confirm_email_mismatch")
	}
	return Canonical(email), nil
}

// question is confirm_yes_no, the consequence, and confirm_pinned_by when
// pinned.
func question(p Prompt) string {
	lines := []string{assets.Message("confirm_yes_no", "kind", p.Kind, "name", p.Name, "id", p.ID), p.Consequence}
	if p.PinnedBy > 0 {
		lines = append(lines, assets.Message("confirm_pinned_by", "count", strconv.FormatUint(p.PinnedBy, 10)))
	}
	return strings.Join(lines, "\n")
}
