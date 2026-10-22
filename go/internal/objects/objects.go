// Package objects is the /platform/objects family every kind shares
// (16.10.26v §8; 16.10.26ad D2), and the verbs of the kinds that are
// nothing more: OAuth clients, embeds, inbound and outbound webhooks, and
// canvases' content and lifecycle. List every page, read, rename, create
// and publish, the body update that respects the one editable version,
// delete with the typed email, and the one-time secret mint.
package objects

import (
	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/httpx"
	"getlingara.com/cli/internal/jsonv"
)

// Kind is a CLI word and the 16.10.26v kind behind it.
type Kind struct {
	// Word is routes.toml's word: webhook_outbound.
	Word string
	// Kind is the platform kind: webhook_out.
	Kind string
	// Label is {kind} in confirm_yes_no.
	Label string
}

// The five kinds.
var (
	OAuthClient     = Kind{"oauth_client", "oauth_client", "OAuth client"}
	App             = Kind{"app", "app", "app"}
	Embed           = Kind{"embed", "embed", "embed"}
	WebhookInbound  = Kind{"webhook_inbound", "webhook_in", "inbound webhook"}
	WebhookOutbound = Kind{"webhook_outbound", "webhook_out", "outbound webhook"}
)

func send(c *app.Ctx, route httpx.Route, vars []httpx.Pair, body any) (any, *core.Fail) {
	return c.Call(httpx.Request{Route: route, Vars: vars, JSON: body})
}

func idVar(id string) []httpx.Pair { return []httpx.Pair{{"id", id}} }

// Pages is every item of every page of route, followed by next_cursor (B1).
func Pages(c *app.Ctx, route httpx.Route, vars, query []httpx.Pair) ([]any, *core.Fail) {
	items := []any{}
	cursor := ""
	for {
		q := append([]httpx.Pair(nil), query...)
		if cursor != "" {
			q = append(q, httpx.Pair{"cursor", cursor})
		}
		page, fail := c.Call(httpx.Request{Route: route, Vars: vars, Query: q})
		if fail != nil {
			return nil, fail
		}
		items = append(items, jsonv.Arr(jsonv.Get(page, "items"))...)
		next, ok := jsonv.Str(jsonv.Get(page, "next_cursor"))
		if !ok {
			return items, nil
		}
		cursor = next
	}
}

// List is every object of kind k.
func List(c *app.Ctx, k Kind) ([]any, *core.Fail) {
	return Pages(c, httpx.Object(k.Word, "list"), nil, []httpx.Pair{{"kind", k.Kind}})
}

// Get is one object.
func Get(c *app.Ctx, k Kind, id string) (any, *core.Fail) {
	return send(c, httpx.Object(k.Word, "get"), idVar(id), nil)
}

// Rename is the object-level name change.
func Rename(c *app.Ctx, k Kind, id, name string) (any, *core.Fail) {
	return send(c, httpx.Object(k.Word, "rename"), idVar(id), jsonv.Obj("name", name))
}

// Create is the object and its first draft: {kind, name, parent_id?, body}.
func Create(c *app.Ctx, k Kind, name, parent string, body any) (any, *core.Fail) {
	req := jsonv.Obj("kind", k.Kind, "name", name, "body", body)
	if parent != "" {
		req.Set("parent_id", parent)
	}
	return send(c, httpx.Object(k.Word, "create"), nil, req)
}

// VersionOp is one version-scoped call with no body.
func VersionOp(c *app.Ctx, k Kind, op, id, semver string) (any, *core.Fail) {
	return send(c, httpx.Object(k.Word, op), []httpx.Pair{{"id", id}, {"semver", semver}}, nil)
}

// PublishIfPrivate publishes a version of a private object at once: a
// private object skips review (16.10.26v §4). A public one stays a draft
// for apps submit. nil when nothing was published.
func PublishIfPrivate(c *app.Ctx, k Kind, object any, semver string) (any, *core.Fail) {
	if jsonv.Text(object, "visibility") == "public" || semver == "" {
		return nil, nil
	}
	return VersionOp(c, k, "publish", jsonv.Text(object, "id"), semver)
}

// Delete is the destructive request: the canonical email in its body
// (B3.6).
func Delete(c *app.Ctx, k Kind, id, version, email string) (any, *core.Fail) {
	confirm := jsonv.Obj("confirm", email)
	if version != "" {
		return send(c, httpx.Object(k.Word, "delete_version"), []httpx.Pair{{"id", id}, {"semver", version}}, confirm)
	}
	return send(c, httpx.Object(k.Word, "delete"), idVar(id), confirm)
}

// SecretCreate is {secret_id, prefix, secret, expires_at}, revealed once.
func SecretCreate(c *app.Ctx, k Kind, id string) (any, *core.Fail) {
	return send(c, httpx.Object(k.Word, "secret_create"), idVar(id), nil)
}

// PrintGet is a get printed: the server's JSON, or B1's FIELD VALUE table.
func PrintGet(c *app.Ctx, k Kind, id string) *core.Fail {
	v, fail := Get(c, k, id)
	if fail != nil {
		return fail
	}
	c.Out.Value(v)
	return nil
}

// PrintList is every page of k, printed: one JSON array, or the schema's
// columns.
func PrintList(c *app.Ctx, k Kind, columns []core.Column) *core.Fail {
	items, fail := List(c, k)
	if fail != nil {
		return fail
	}
	c.Out.List(items, columns)
	return nil
}

// Columns is a list's human columns, heading then member, in pairs.
func Columns(pairs ...string) []core.Column {
	out := make([]core.Column, 0, len(pairs)/2)
	for i := 0; i+1 < len(pairs); i += 2 {
		out = append(out, core.Column{Heading: pairs[i], Key: pairs[i+1]})
	}
	return out
}
