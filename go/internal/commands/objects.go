package commands

import (
	"strconv"

	"github.com/spf13/cobra"

	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/objects"
)

// The object topics' leaves: oauth_client (alias client) and embed. Each
// leaf reads its flags into the handler's own arguments; the handlers are
// package objects'.

func itoa(n int) string { return strconv.Itoa(n) }

func deleteArgs(in input) objects.DeleteArgs {
	return objects.DeleteArgs{ID: in.arg(0), Version: in.str("version"), Confirm: in.confirmFlags()}
}

// deleteLeaf is every object delete: an id, --version and the ritual.
func (b *builder) deleteLeaf(parent *cobra.Command, short string, h handler) {
	b.leaf(parent, "delete", short, h).str("version", "Delete only this version").confirm()
}

func (b *builder) oauthClient(root *cobra.Command) {
	g := b.group(root, "oauth_client", "OAuth clients: the credentials your servers, apps and CI use.", "client")
	b.leaf(g, "create", "Create an OAuth client; a confidential one's secret is shown once.", clientCreate).
		str("name", "The client's name").need("name").
		strs("scope", "A scope the client may ask for").
		str("type", "Whether the client keeps a secret").
		str("billing-mode", "How its usage is billed").
		strs("redirect-uri", "A redirect URI it may use").
		boolean("ci", "A CI client: confidential, holding the four platform and canvas scopes").
		conflicts([2]string{"ci", "type"}, [2]string{"ci", "scope"})
	b.leaf(g, "list", "List your OAuth clients.", func(c *app.Ctx, _ input) *core.Fail {
		return objects.PrintList(c, objects.OAuthClient, objects.ClientColumns)
	})
	b.leaf(g, "get", "Show one OAuth client.", func(c *app.Ctx, in input) *core.Fail {
		return objects.PrintGet(c, objects.OAuthClient, in.arg(0))
	})
	b.leaf(g, "update", "Rename an OAuth client, or replace its scopes, API version or redirect URIs.", clientUpdate).
		str("name", "The new name").strs("scope", "Replaces the scopes").
		str("api-version", "The API version it is pinned to").strs("redirect-uri", "Replaces the redirect URIs")
	b.deleteLeaf(g, "Delete an OAuth client and everything under it, or one version.", func(c *app.Ctx, in input) *core.Fail {
		return objects.DeleteObject(c, objects.OAuthClient, deleteArgs(in), objects.ClientConsequence)
	})
	s := b.group(g, "secret", "A client's secrets. Rotate by creating the new one, deploying it, then deleting the old one.")
	b.leaf(s, "create", "Mint a new secret, shown once. The old one stays live.", func(c *app.Ctx, in input) *core.Fail {
		return objects.ClientSecretCreate(c, in.arg(0))
	})
	b.leaf(s, "delete", "Revoke a secret.", func(c *app.Ctx, in input) *core.Fail {
		return objects.ClientSecretDelete(c, in.arg(0), in.arg(1), in.confirmFlags())
	}).confirm()
}

func clientCreate(c *app.Ctx, in input) *core.Fail {
	a := objects.ClientCreateArgs{
		Name: in.str("name"), Scopes: in.list("scope"), Type: in.str("type"), BillingMode: in.str("billing-mode"),
		RedirectURIs: in.list("redirect-uri"), CI: in.flag("ci"),
	}
	if a.Type == "" {
		a.Type = "confidential"
	}
	if a.BillingMode == "" {
		a.BillingMode = "allowance"
	}
	_, fail := objects.ClientCreate(c, a)
	return fail
}

func clientUpdate(c *app.Ctx, in input) *core.Fail {
	return objects.ClientUpdate(c, objects.ClientUpdateArgs{
		ID: in.arg(0), Name: in.opt("name"), Scopes: in.list("scope"), APIVersion: in.opt("api-version"),
		RedirectURIs: in.list("redirect-uri"),
	})
}

func (b *builder) embed(root *cobra.Command) {
	g := b.group(root, "embed", "Lingara inside your game or website: an embed and the metered OAuth client behind it.")
	b.leaf(g, "create", "Create an embed and its metered OAuth client, and show the client's secret once.",
		func(c *app.Ctx, in input) *core.Fail {
			return objects.EmbedCreate(c, in.str("name"), in.list("origin"), in.list("scope"))
		}).
		str("name", "The embed's name").need("name").
		strs("origin", "A web origin allowed to use it (up to 10)").strs("scope", "A scope beside embed:mint")
	b.leaf(g, "list", "List your embeds.", func(c *app.Ctx, _ input) *core.Fail {
		return objects.PrintList(c, objects.Embed, objects.EmbedColumns)
	})
	b.leaf(g, "get", "Show one embed.", func(c *app.Ctx, in input) *core.Fail {
		return objects.PrintGet(c, objects.Embed, in.arg(0))
	})
	b.leaf(g, "update", "Rename an embed, or replace its origins.", func(c *app.Ctx, in input) *core.Fail {
		return objects.EmbedUpdate(c, in.arg(0), in.opt("name"), in.list("origin"))
	}).str("name", "The new name").strs("origin", "Replaces the allowed origins")
	b.deleteLeaf(g, "Delete an embed and its OAuth client, or one version.", func(c *app.Ctx, in input) *core.Fail {
		return objects.DeleteObject(c, objects.Embed, deleteArgs(in), objects.ParentConsequence("consequence_embed"))
	})
}
