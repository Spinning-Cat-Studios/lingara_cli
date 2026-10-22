package core

import (
	"fmt"
	"strings"

	"getlingara.com/cli/internal/assets"
	"getlingara.com/cli/internal/jsonv"
)

// The two codes conformance/routes.toml names, as this kit keys them.
const (
	PlatformDisabledCode = "platform_disabled"
	PlatformAccessCode   = "platform_access_not_enabled"
)

// Answer is a non-2xx response, with what the rules read about the request
// behind it.
type Answer struct {
	Route string
	// TokenRoute is oauth.token or oauth.device_authorization.
	TokenRoute bool
	// PlatformRoute is under /platform/, where the dark 404 is spoken.
	PlatformRoute bool
	// Bearer is whether the request carried Authorization: Bearer (rule 2).
	Bearer     bool
	Status     int
	Body       []byte
	RetryAfter int
	HasRetry   bool
}

func (a Answer) member(key string) (string, bool) {
	v, err := jsonv.Parse(a.Body)
	if err != nil {
		return "", false
	}
	return jsonv.Str(jsonv.Get(v, key))
}

func (a Answer) has(key string) bool {
	_, ok := a.member(key)
	return ok
}

// coded is the platform's and /v1's {code, error} envelope.
func (a Answer) coded() bool {
	return a.has("code") && a.has("error")
}

// oauthEnvelope is RFC 6749 / 8628's: an error member and no code.
func (a Answer) oauthEnvelope() bool {
	return a.has("error") && !a.has("code")
}

func (a Answer) blank() bool {
	return strings.TrimSpace(string(a.Body)) == ""
}

// Verdict is a rule's answer: refresh and replay once (rule 2), or an exit.
type Verdict struct {
	Refresh bool
	Exit    Exit
}

// FailFor is the refusal B2 prints for exit: the server's own code and text
// where it sent them, the kit's sentence where it did not.
func FailFor(a Answer, exit Exit) *Fail {
	var fail *Fail
	switch exit {
	case ExitPlatformDisabled:
		fail = NewFail(exit, PlatformDisabledCode, assets.Message("platform_disabled"))
	case ExitFailure:
		fail = NewFail(ExitFailure, "unexpected_response", "unexpected response from "+a.Route)
	case ExitAuthRequired:
		code := memberOr(a, "error", "auth_required")
		fail = NewFail(exit, code, memberOr(a, "error_description", a.Route+" refused the credentials"))
	default:
		code := memberOr(a, "code", exitWord(exit))
		fail = NewFail(exit, code, memberOr(a, "error", fmt.Sprintf("%s answered HTTP %d", a.Route, a.Status)))
	}
	if a.HasRetry && exit == ExitUnavailable {
		fail.Message = fmt.Sprintf("%s (retry after %d s)", fail.Message, a.RetryAfter)
	}
	fail.Body = string(a.Body)
	return fail
}

func memberOr(a Answer, key, fallback string) string {
	if v, ok := a.member(key); ok {
		return v
	}
	return fallback
}

func exitWord(exit Exit) string {
	if exit == ExitUnavailable {
		return "unavailable"
	}
	return "refused"
}
