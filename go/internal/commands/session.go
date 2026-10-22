package commands

import (
	"github.com/spf13/cobra"

	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/assets"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/core/store"
	"getlingara.com/cli/internal/httpx"
	"getlingara.com/cli/internal/jsonv"
)

// login, logout and whoami (16.10.26ad D3). Under CI credentials there is
// nothing to sign in or out of, so both exit usage with
// login_ci_not_needed.

func (b *builder) session(root *cobra.Command) {
	b.leaf(root, "login", "Sign in through the browser, or with a device code.", login).
		boolean("device", "Sign in with a code shown here, for a machine with no browser")
	b.leaf(root, "logout", "Revoke and forget the stored credentials.", logout)
	b.leaf(root, "whoami", "Show the developer the credentials belong to.", whoami)
}

func ciNotNeeded() *core.Fail {
	return core.NewFail(core.ExitUsage, "login_ci_not_needed", assets.Message("login_ci_not_needed"))
}

func login(c *app.Ctx, in input) *core.Fail {
	if _, _, ci := c.CIPair(); ci {
		return ciNotNeeded()
	}
	var tokens any
	var fail *core.Fail
	if in.flag("device") {
		tokens, fail = c.DeviceLogin()
	} else {
		tokens, fail = c.BrowserLogin()
	}
	if fail != nil {
		return fail
	}
	record := store.ProfileRecord{}
	app.ReadTokens(tokens).Apply(&record, c.App.HTTP.Clock.Now())
	// The new token, for this one read: whose is it?
	c.App.Session = &app.Session{Record: &record, Access: record.AccessToken, Scopes: record.Scopes}
	me, fail := c.Call(httpx.Request{Route: httpx.Fixed("developer.me")})
	if fail != nil {
		return fail
	}
	record.DeveloperID, record.Email = jsonv.Text(me, "developer_id"), jsonv.Text(me, "email")
	if fail := c.SaveRecord(record); fail != nil {
		return fail
	}
	c.Out.Note(assets.Message("login_signed_in", "email", record.Email))
	c.Out.Nothing()
	return nil
}

// logout revokes the refresh token (RFC 7009) and forgets the profile. A
// revoke that fails, the network being down say, still forgets it and
// warns.
func logout(c *app.Ctx, _ input) *core.Fail {
	if _, _, ci := c.CIPair(); ci {
		return ciNotNeeded()
	}
	record, fail := c.App.Stores.Load(c.Profile)
	if fail != nil {
		return fail
	}
	if record == nil {
		return core.Auth("not signed in: nothing to sign out of")
	}
	form := []httpx.Pair{{"token", record.RefreshToken}, {"token_type_hint", "refresh_token"}, {"client_id", c.App.Endpoints.ClientID}}
	reply, fail := c.App.HTTP.Send(httpx.Request{Route: httpx.Fixed("oauth.revoke"), Form: form}, httpx.Auth{Bearer: record.AccessToken})
	switch {
	case fail != nil:
		c.Out.Note("warning: the refresh token could not be revoked (" + fail.Message + "); forgetting it here anyway")
	case reply.Status < 200 || reply.Status >= 300:
		c.Out.Note("warning: the refresh token could not be revoked (HTTP " + itoa(reply.Status) + "); forgetting it here anyway")
	}
	if fail := c.App.Stores.Delete(c.Profile); fail != nil {
		return fail
	}
	c.App.Session = nil
	c.Out.Nothing()
	return nil
}

// whoami is EMAIL DEVELOPER SCOPES PROFILE, SCOPES from the token response.
func whoami(c *app.Ctx, _ input) *core.Fail {
	me, fail := c.Call(httpx.Request{Route: httpx.Fixed("developer.me")})
	if fail != nil {
		return fail
	}
	if c.Out.JSON {
		c.Out.JSONValue(me)
		return nil
	}
	scopes := ""
	if c.App.Session != nil {
		scopes = c.App.Session.Scopes
	}
	row := []string{core.Cell(jsonv.Get(me, "email")), core.Cell(jsonv.Get(me, "developer_id")), scopes, c.Profile}
	c.Out.Print(core.Render([]string{"EMAIL", "DEVELOPER", "SCOPES", "PROFILE"}, [][]string{row}))
	return nil
}
