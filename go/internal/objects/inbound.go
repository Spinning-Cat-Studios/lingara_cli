package objects

import (
	"strings"

	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/jsonv"
)

// Inbound webhooks (16.10.26ad D2): a webhook_in object (body
// {event_types[]}) on a confidential client, the credential a sender uses
// for POST /v1/events. events:write comes from the child's projection, so
// the client's own body never names it, and deleting the webhook clears it.

// InboundColumns is webhook inbound list's.
var InboundColumns = Columns("ID", "id", "NAME", "name", "CLIENT", "parent_id", "EVENTS", "event_types")

// inboundBody: no --event omits event_types, which 16.10.26v §5 projects as
// every inbound type.
func inboundBody(events []string) *jsonv.Object {
	if len(events) == 0 {
		return jsonv.NewObject()
	}
	return jsonv.Obj("event_types", events)
}

// InboundCreate makes the client, its secret, then the webhook, and says
// where senders post.
func InboundCreate(c *app.Ctx, name string, events []string) *core.Fail {
	clientBody := jsonv.Obj("client_type", "confidential", "allowed_scopes", []string{})
	made := &Creation{}
	hookID := ""
	client, fail := made.Object(c, OAuthClient, name, "", clientBody)
	if fail == nil {
		clientID := jsonv.Text(client, "id")
		if fail = made.MintSecret(c, OAuthClient, clientID); fail == nil {
			var hook any
			hook, fail = made.Object(c, WebhookInbound, name, clientID, inboundBody(events))
			hookID = jsonv.Text(hook, "id")
		}
	}
	hook, fail := made.Conclude(c, WebhookInbound, hookID, fail)
	if fail != nil {
		return fail
	}
	ingest := strings.TrimRight(c.App.HTTP.API, "/") + "/v1/events"
	c.Out.Note("Senders POST to " + ingest + " as client " + jsonv.TextOr(hook, "-", "client_id") +
		", with its secret and an Idempotency-Key header.")
	return nil
}

// InboundUpdate: --name renames; --event replaces the event types.
func InboundUpdate(c *app.Ctx, id string, name *string, events []string) *core.Fail {
	var last any
	var fail *core.Fail
	if name != nil {
		if last, fail = Rename(c, WebhookInbound, id, *name); fail != nil {
			return fail
		}
	}
	if len(events) > 0 {
		if last, fail = BodyUpdate(c, WebhookInbound, id, func(b *jsonv.Object) { b.Set("event_types", events) }); fail != nil {
			return fail
		}
	}
	if last == nil {
		return core.Usage("nothing to update: pass --name or --event")
	}
	c.Out.Value(last)
	return nil
}
