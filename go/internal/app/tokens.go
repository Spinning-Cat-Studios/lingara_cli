package app

import (
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/core/store"
	"getlingara.com/cli/internal/jsonv"
)

// Tokens is a token response (RFC 6749 §5.1), read leniently: a missing
// expires_in is an hour.
type Tokens struct {
	Access     string
	Refresh    string
	HasRefresh bool
	Scope      string
	HasScope   bool
	ExpiresIn  int64
}

// ReadTokens reads a token response.
func ReadTokens(v any) Tokens {
	t := Tokens{Access: jsonv.Text(v, "access_token"), ExpiresIn: 3600}
	t.Refresh, t.HasRefresh = jsonv.Str(jsonv.Get(v, "refresh_token"))
	t.Scope, t.HasScope = jsonv.Str(jsonv.Get(v, "scope"))
	if n, ok := jsonv.Int(jsonv.Get(v, "expires_in")); ok {
		t.ExpiresIn = n
	}
	return t
}

// Apply writes these tokens into record: the refresh token rotates when one
// was sent, and scopes is rewritten whenever the answer carried scope.
func (t Tokens) Apply(record *store.ProfileRecord, now int64) {
	record.AccessToken = t.Access
	if t.HasRefresh {
		record.RefreshToken = t.Refresh
	}
	if t.HasScope {
		record.Scopes = t.Scope
	}
	record.AccessExpiresAt = core.FormatTime(now + t.ExpiresIn)
}
