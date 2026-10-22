package objects

import (
	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/jsonv"
)

// lingara embed … (16.10.26ad D2): an embed object (body {origins[]}) on a
// metered, confidential client, which holds embed:mint through the embed's
// projection, never through its own body (16.10.26v §1).

// EmbedColumns is embed list's.
var EmbedColumns = Columns("ID", "id", "NAME", "name", "CLIENT", "parent_id", "ORIGINS", "origins", "SERVING", "serving_version")

// EmbedCreate makes the client first (confidential, metered), its secret,
// then the embed.
func EmbedCreate(c *app.Ctx, name string, origins, scopes []string) *core.Fail {
	clientBody := jsonv.Obj("client_type", "confidential", "billing_mode", "metered", "allowed_scopes", nonNil(scopes))
	made := &Creation{}
	embedID := ""
	client, fail := made.Object(c, OAuthClient, name, "", clientBody)
	if fail == nil {
		clientID := jsonv.Text(client, "id")
		if fail = made.MintSecret(c, OAuthClient, clientID); fail == nil {
			var embed any
			embed, fail = made.Object(c, Embed, name, clientID, jsonv.Obj("origins", nonNil(origins)))
			embedID = jsonv.Text(embed, "id")
		}
	}
	_, fail = made.Conclude(c, Embed, embedID, fail)
	return fail
}

// EmbedUpdate: --name renames; --origin replaces the origins in a body
// update.
func EmbedUpdate(c *app.Ctx, id string, name *string, origins []string) *core.Fail {
	var last any
	var fail *core.Fail
	if name != nil {
		if last, fail = Rename(c, Embed, id, *name); fail != nil {
			return fail
		}
	}
	if len(origins) > 0 {
		if last, fail = BodyUpdate(c, Embed, id, func(b *jsonv.Object) { b.Set("origins", origins) }); fail != nil {
			return fail
		}
	}
	if last == nil {
		return core.Usage("nothing to update: pass --name or --origin")
	}
	c.Out.Value(last)
	return nil
}
