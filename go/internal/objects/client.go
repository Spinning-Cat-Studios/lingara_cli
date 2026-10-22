package objects

import (
	"strconv"
	"strings"

	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/assets"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/httpx"
	"getlingara.com/cli/internal/jsonv"
)

// lingara oauth_client … (16.10.26ad D1, D2). Rotation is two commands,
// because it is two calls: secret create while the old secret stays live,
// then secret delete once the new one is deployed.

// ClientColumns is oauth_client list's.
var ClientColumns = Columns("ID", "id", "NAME", "name", "TYPE", "client_type", "SCOPES", "allowed_scopes", "CREATED", "created_at")

// ClientCreateArgs is oauth_client create's flags.
type ClientCreateArgs struct {
	Name         string
	Scopes       []string
	Type         string
	BillingMode  string
	RedirectURIs []string
	CI           bool
}

// ClientBody is the client's version body (16.10.26v §1). --ci is
// confidential with the four scopes; whether the client may be one is the
// server's predicate.
func ClientBody(a ClientCreateArgs) *jsonv.Object {
	clientType, scopes := a.Type, a.Scopes
	if a.CI {
		clientType, scopes = "confidential", strings.Split(app.Scopes, " ")
	}
	return jsonv.Obj("client_type", clientType, "allowed_scopes", nonNil(scopes),
		"billing_mode", a.BillingMode, "redirect_uris", nonNil(a.RedirectURIs))
}

func nonNil(s []string) []string {
	if s == nil {
		return []string{}
	}
	return s
}

// ClientCreate creates, publishes, mints the confidential client's
// one-time secret, and reads.
func ClientCreate(c *app.Ctx, a ClientCreateArgs) (any, *core.Fail) {
	made := &Creation{}
	client, fail := made.Object(c, OAuthClient, a.Name, "", ClientBody(a))
	id := jsonv.Text(client, "id")
	if fail == nil && (a.CI || a.Type == "confidential") {
		fail = made.MintSecret(c, OAuthClient, id)
	}
	return made.Conclude(c, OAuthClient, id, fail)
}

// ClientUpdateArgs is oauth_client update's flags; nil is not given.
type ClientUpdateArgs struct {
	ID           string
	Name         *string
	Scopes       []string
	APIVersion   *string
	RedirectURIs []string
}

// ClientUpdate: --name is a rename; any other flag is a body update.
func ClientUpdate(c *app.Ctx, a ClientUpdateArgs) *core.Fail {
	var last any
	var fail *core.Fail
	if a.Name != nil {
		if last, fail = Rename(c, OAuthClient, a.ID, *a.Name); fail != nil {
			return fail
		}
	}
	if len(a.Scopes) > 0 || a.APIVersion != nil || len(a.RedirectURIs) > 0 {
		if last, fail = BodyUpdate(c, OAuthClient, a.ID, a.overlay); fail != nil {
			return fail
		}
	}
	if last == nil {
		return core.Usage("nothing to update: pass --name, --scope, --api-version or --redirect-uri")
	}
	c.Out.Value(last)
	return nil
}

// overlay writes the given flags onto the client's body.
func (a ClientUpdateArgs) overlay(body *jsonv.Object) {
	if len(a.Scopes) > 0 {
		body.Set("allowed_scopes", a.Scopes)
	}
	if a.APIVersion != nil {
		body.Set("api_version", *a.APIVersion)
	}
	if len(a.RedirectURIs) > 0 {
		body.Set("redirect_uris", a.RedirectURIs)
	}
}

// ClientConsequence counts the children 16.10.26v §7's cascade deletes, by
// kind.
func ClientConsequence(object any) string {
	count := func(kinds ...string) string {
		n := 0
		for _, child := range jsonv.Arr(jsonv.Get(object, "children")) {
			for _, k := range kinds {
				if jsonv.Text(child, "kind") == k {
					n++
				}
			}
		}
		return strconv.Itoa(n)
	}
	return assets.Message("consequence_oauth_client",
		"apps", count("app"), "embeds", count("embed"), "webhooks", count("webhook_in", "webhook_out"))
}

// SecretCreate prints a new secret, revealed once.
func ClientSecretCreate(c *app.Ctx, clientID string) *core.Fail {
	minted, fail := SecretCreate(c, OAuthClient, clientID)
	if fail != nil {
		return fail
	}
	c.Out.Value(minted)
	c.Out.Note(secretOnce)
	return nil
}

// ClientSecretDelete revokes a secret. A secret has no read of its own, so
// the prompt names the client's.
func ClientSecretDelete(c *app.Ctx, clientID, secretID string, flags core.ConfirmFlags) *core.Fail {
	email, fail := core.ConfirmDestructive(c, flags, func() (core.Prompt, *core.Fail) {
		client, fail := Get(c, OAuthClient, clientID)
		if fail != nil {
			return core.Prompt{}, fail
		}
		p := NewPrompt(OAuthClient, client, secretID, "", Fixed("consequence_secret"))
		p.Kind, p.PinnedBy = "secret", 0
		return p, nil
	})
	if fail != nil {
		return fail
	}
	vars := []httpx.Pair{{"id", clientID}, {"secret_id", secretID}}
	if _, fail := send(c, httpx.Object(OAuthClient.Word, "secret_delete"), vars, jsonv.Obj("confirm", email)); fail != nil {
		return fail
	}
	c.Out.Nothing()
	return nil
}
