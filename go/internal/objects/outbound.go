package objects

import (
	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/httpx"
	"getlingara.com/cli/internal/jsonv"
)

// Outbound webhooks (16.10.26ad D2): a webhook_out object (body {url,
// event_types[]}) under its client. A body update keeps the endpoint and its
// signing secret; enabled is object-level, never version body.

// OutboundColumns is webhook outbound list's.
var OutboundColumns = Columns("ID", "id", "CLIENT", "parent_id", "URL", "url", "EVENTS", "event_types", "ENABLED", "enabled")

// OutboundCreate creates, publishes, mints the signing secret (revealed
// once) and reads.
func OutboundCreate(c *app.Ctx, client, url string, events []string) (any, *core.Fail) {
	body := jsonv.Obj("url", url, "event_types", nonNil(events))
	// The verb takes no name, so the object is named by its URL, cut to the
	// 80 characters a platform object name may hold (16.10.26v).
	name := []rune(url)
	if len(name) > 80 {
		name = name[:80]
	}
	made := &Creation{}
	hook, fail := made.Object(c, WebhookOutbound, string(name), client, body)
	id := jsonv.Text(hook, "id")
	if fail == nil {
		fail = made.MintSecret(c, WebhookOutbound, id)
	}
	return made.Conclude(c, WebhookOutbound, id, fail)
}

// ObjectAction is enable, disable or test: object-level, never versioned.
func ObjectAction(c *app.Ctx, op, id string) (any, *core.Fail) {
	return c.Call(httpx.Request{Route: httpx.Object(WebhookOutbound.Word, op), Vars: idVar(id)})
}

// OutboundUpdateArgs is webhook outbound update's flags.
type OutboundUpdateArgs struct {
	ID      string
	URL     *string
	Events  []string
	Enable  bool
	Disable bool
}

// overlay writes --url and --event onto the webhook's body.
func (a OutboundUpdateArgs) overlay(body *jsonv.Object) {
	if a.URL != nil {
		body.Set("url", *a.URL)
	}
	if len(a.Events) > 0 {
		body.Set("event_types", a.Events)
	}
}

// OutboundUpdate: --url/--event are a body update; --enable/--disable
// follow it.
func OutboundUpdate(c *app.Ctx, a OutboundUpdateArgs) (any, *core.Fail) {
	var last any
	var fail *core.Fail
	if a.URL != nil || len(a.Events) > 0 {
		if last, fail = BodyUpdate(c, WebhookOutbound, a.ID, a.overlay); fail != nil {
			return nil, fail
		}
	}
	if a.Enable || a.Disable {
		op := "disable"
		if a.Enable {
			op = "enable"
		}
		if last, fail = ObjectAction(c, op, a.ID); fail != nil {
			return nil, fail
		}
	}
	if last == nil {
		return nil, core.Usage("nothing to update: pass --url, --event, --enable or --disable")
	}
	return last, nil
}
