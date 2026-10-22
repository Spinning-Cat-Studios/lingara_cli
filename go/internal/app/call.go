package app

import (
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/core/store"
	"getlingara.com/cli/internal/httpx"
	"getlingara.com/cli/internal/jsonv"
)

// refresh is rule 2's one refresh: the stored refresh token, or a fresh CI
// mint. A refused refresh never deletes the stored credential (D3).
func (c *Ctx) refresh() *core.Fail {
	s := c.App.Session
	if s == nil {
		return core.Auth("not signed in: run `lingara login`")
	}
	if s.Record == nil {
		tokens, fail := c.mint(s.CIID, s.CISecret)
		if fail != nil {
			return fail
		}
		s.Access = tokens.Access
		return nil
	}
	record := *s.Record
	form := []httpx.Pair{
		{"grant_type", "refresh_token"},
		{"refresh_token", record.RefreshToken},
		{"client_id", c.App.Endpoints.ClientID},
	}
	v, fail := c.TokenPost("oauth.token", form, httpx.Auth{})
	if fail != nil {
		return loginAgain(fail)
	}
	ReadTokens(v).Apply(&record, c.App.HTTP.Clock.Now())
	if fail := c.SaveRecord(record); fail != nil {
		return fail
	}
	c.App.Session = &Session{Record: &record, Access: record.AccessToken, Email: record.Email, Scopes: record.Scopes}
	return nil
}

// loginAgain: a refused refresh says "run `lingara login`", unless it is
// D8's dark signal, which has its own sentence.
func loginAgain(fail *core.Fail) *core.Fail {
	if fail.Exit == core.ExitAuthRequired {
		return fail.Hint("run `lingara login`")
	}
	return fail
}

// SaveRecord writes the profile record, printing the store's one fallback
// warning.
func (c *Ctx) SaveRecord(record store.ProfileRecord) *core.Fail {
	warning, fail := c.App.Stores.Save(c.Profile, record)
	if fail != nil {
		return fail
	}
	if warning != "" {
		c.Out.Note(warning)
	}
	return nil
}

// Call is one authenticated call: send, refresh and replay once on a 401,
// classify anything else.
func (c *Ctx) Call(req httpx.Request) (any, *core.Fail) {
	token, fail := c.Bearer()
	if fail != nil {
		return nil, fail
	}
	reply, fail := c.App.HTTP.Send(req, httpx.Auth{Bearer: token})
	if fail != nil {
		return nil, fail
	}
	if ok(reply.Status) {
		return bodyJSON(reply, req.Route)
	}
	if !core.Classify(answer(req.Route, reply, true)).Refresh {
		return nil, Refusal(req.Route, reply, true)
	}
	if fail := c.refresh(); fail != nil {
		return nil, fail
	}
	token, fail = c.Bearer()
	if fail != nil {
		return nil, fail
	}
	replay, fail := c.App.HTTP.Send(req, httpx.Auth{Bearer: token})
	switch {
	case fail != nil:
		return nil, fail
	case ok(replay.Status):
		return bodyJSON(replay, req.Route)
	case replay.Status == 401:
		return nil, core.Auth("the platform refused the credentials after a refresh: run `lingara login`")
	}
	return nil, Refusal(req.Route, replay, true)
}

// Email is the email --confirm and the typed line are compared against.
func (c *Ctx) Email() (string, *core.Fail) {
	if _, fail := c.Bearer(); fail != nil {
		return "", fail
	}
	if c.App.Session.Email != "" {
		return c.App.Session.Email, nil
	}
	me, fail := c.Call(httpx.Request{Route: httpx.Fixed("developer.me")})
	if fail != nil {
		return "", fail
	}
	email := jsonv.Text(me, "email")
	if c.App.Session != nil {
		c.App.Session.Email = email
	}
	return email, nil
}
