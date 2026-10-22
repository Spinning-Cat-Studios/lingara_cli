package app

import (
	"strings"

	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/core/store"
	"getlingara.com/cli/internal/httpx"
	"getlingara.com/cli/internal/jsonv"
)

// The signed-in session and every authenticated call (D3, contract B2 rule
// 2): a 401 to a bearer request refreshes once (a fresh client_credentials
// mint under CI) and replays once; a second 401 is auth_required, and any
// other answer to the replay is classified again.

// Scopes is the four scopes, in one grant: the platform pair opens
// /platform/*, the canvas pair /v1/canvases (16.10.26u §3).
const Scopes = "platform:read platform:write canvas:read canvas:write"

// refreshSkewSecs: a stored access token this close to expiry is refreshed
// first (D3).
const refreshSkewSecs = 60

// Session is the credential in use. CI credentials (LINGARA_PLATFORM_*)
// live in memory only; a stored one is the profile's record.
type Session struct {
	CIID, CISecret string
	Record         *store.ProfileRecord
	Access         string
	// Email is the one --confirm is checked against: the record's, or under
	// CI developer.me's, read once (D5 step 2).
	Email  string
	Scopes string
}

// bodyJSON is a 2xx body as JSON; an empty one is {}.
func bodyJSON(reply httpx.WireResponse, route httpx.Route) (any, *core.Fail) {
	if strings.TrimSpace(string(reply.Body)) == "" {
		return jsonv.NewObject(), nil
	}
	v, err := jsonv.Parse(reply.Body)
	if err != nil {
		return nil, core.Failure("unexpected response from " + route.Name)
	}
	return v, nil
}

func answer(route httpx.Route, reply httpx.WireResponse, bearer bool) core.Answer {
	secs, ok := httpx.RetryAfter(reply)
	return core.Answer{
		Route: route.Name, TokenRoute: route.IsToken(), PlatformRoute: route.IsPlatform(),
		Bearer: bearer, Status: reply.Status, Body: reply.Body, RetryAfter: secs, HasRetry: ok,
	}
}

// Refusal is the refusal for a non-2xx reply that is not rule 2's.
func Refusal(route httpx.Route, reply httpx.WireResponse, bearer bool) *core.Fail {
	a := answer(route, reply, bearer)
	verdict := core.Classify(a)
	exit := verdict.Exit
	if verdict.Refresh {
		exit = core.ExitAuthRequired
	}
	fail := core.Advise(core.FailFor(a, exit))
	body, _ := jsonv.Parse(reply.Body)
	return core.AdviseLingaraCode(fail, jsonv.Text(body, "lingara_code"))
}

func ok(status int) bool { return status >= 200 && status < 300 }

// TokenPost is a token-endpoint form POST: no bearer, classified as a
// token route.
func (c *Ctx) TokenPost(name string, form []httpx.Pair, auth httpx.Auth) (any, *core.Fail) {
	route := httpx.Fixed(name)
	reply, fail := c.App.HTTP.Send(httpx.Request{Route: route, Form: form}, auth)
	if fail != nil {
		return nil, fail
	}
	if !ok(reply.Status) {
		return nil, Refusal(route, reply, false)
	}
	return bodyJSON(reply, route)
}

func (c *Ctx) mint(id, secret string) (Tokens, *core.Fail) {
	form := []httpx.Pair{{"grant_type", "client_credentials"}, {"scope", Scopes}}
	v, fail := c.TokenPost("oauth.token", form, httpx.Auth{BasicID: id, BasicSecret: secret})
	if fail != nil {
		return Tokens{}, fail
	}
	return ReadTokens(v), nil
}

func (c *Ctx) open() *core.Fail {
	if id, secret, ci := c.CIPair(); ci {
		tokens, fail := c.mint(id, secret)
		if fail != nil {
			return fail
		}
		scopes := tokens.Scope
		if !tokens.HasScope {
			scopes = Scopes
		}
		c.App.Session = &Session{CIID: id, CISecret: secret, Access: tokens.Access, Scopes: scopes}
		return nil
	}
	record, fail := c.App.Stores.Load(c.Profile)
	if fail != nil {
		return fail
	}
	if record == nil {
		return core.Auth("not signed in: run `lingara login`")
	}
	c.App.Session = &Session{Record: record, Access: record.AccessToken, Email: record.Email, Scopes: record.Scopes}
	at, parsed := core.ParseTime(record.AccessExpiresAt)
	if !parsed || at-c.App.HTTP.Clock.Now() < refreshSkewSecs {
		if fail := c.refresh(); fail != nil {
			c.App.Session = nil
			return fail
		}
	}
	return nil
}

// Bearer is the access token, opening the session on first use.
func (c *Ctx) Bearer() (string, *core.Fail) {
	if c.App.Session == nil {
		if fail := c.open(); fail != nil {
			return "", fail
		}
	}
	return c.App.Session.Access, nil
}
