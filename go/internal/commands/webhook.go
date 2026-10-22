package commands

import (
	"github.com/spf13/cobra"

	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/objects"
)

// lingara webhook inbound|outbound … (16.10.26ad D1), plus replace and test
// on outbound.

func (b *builder) webhook(root *cobra.Command) {
	g := b.group(root, "webhook", "Webhooks: events your game sends in, and events Lingara sends out.")
	b.inbound(b.group(g, "inbound", "Inbound webhooks: a confidential client a sender uses for POST /v1/events."))
	b.outbound(b.group(g, "outbound", "Outbound webhooks: events Lingara delivers to your endpoint, signed."))
}

func (b *builder) inbound(g *cobra.Command) {
	b.leaf(g, "create", "Create an inbound webhook and its client, and show the client's secret once.",
		func(c *app.Ctx, in input) *core.Fail {
			return objects.InboundCreate(c, in.str("name"), in.list("event"))
		}).
		str("name", "The webhook's name").need("name").
		strs("event", "An inbound event type it accepts; absent, every one")
	b.leaf(g, "list", "List your inbound webhooks.", func(c *app.Ctx, _ input) *core.Fail {
		return objects.PrintList(c, objects.WebhookInbound, objects.InboundColumns)
	})
	b.leaf(g, "get", "Show one inbound webhook.", func(c *app.Ctx, in input) *core.Fail {
		return objects.PrintGet(c, objects.WebhookInbound, in.arg(0))
	})
	b.leaf(g, "update", "Rename an inbound webhook, or replace its event types.", func(c *app.Ctx, in input) *core.Fail {
		return objects.InboundUpdate(c, in.arg(0), in.opt("name"), in.list("event"))
	}).str("name", "The new name").strs("event", "Replaces the event types")
	b.deleteLeaf(g, "Delete an inbound webhook, or one version. Its client stays.", func(c *app.Ctx, in input) *core.Fail {
		return objects.DeleteObject(c, objects.WebhookInbound, deleteArgs(in), objects.ParentConsequence("consequence_webhook_inbound"))
	})
}

func (b *builder) outbound(g *cobra.Command) {
	b.leaf(g, "create", "Create an outbound webhook, and show its signing secret once.", func(c *app.Ctx, in input) *core.Fail {
		_, fail := objects.OutboundCreate(c, in.str("client"), in.str("url"), in.list("event"))
		return fail
	}).str("client", "The OAuth client it belongs to").str("url", "The endpoint deliveries go to").
		strs("event", "An event type to deliver").need("client", "url", "event")
	b.leaf(g, "list", "List your outbound webhooks.", func(c *app.Ctx, _ input) *core.Fail {
		return objects.PrintList(c, objects.WebhookOutbound, objects.OutboundColumns)
	})
	b.leaf(g, "get", "Show one outbound webhook.", func(c *app.Ctx, in input) *core.Fail {
		return objects.PrintGet(c, objects.WebhookOutbound, in.arg(0))
	})
	b.leaf(g, "update", "Change an outbound webhook's URL or event types, keeping its signing secret, or enable or disable it.",
		outboundUpdate).
		str("url", "The new endpoint").strs("event", "Replaces the event types").
		boolean("enable", "Resume deliveries").boolean("disable", "Pause deliveries").
		conflicts([2]string{"enable", "disable"})
	b.leaf(g, "replace", "Start again: a new endpoint with a new signing secret, then the old one deleted.",
		func(c *app.Ctx, in input) *core.Fail {
			return objects.Replace(c, in.arg(0), in.opt("url"), in.list("event"), in.confirmFlags())
		}).
		str("url", "The new endpoint; absent, the old one's").strs("event", "The new event types; absent, the old one's").confirm()
	b.leaf(g, "test", "Send a test delivery.", func(c *app.Ctx, in input) *core.Fail {
		v, fail := objects.ObjectAction(c, "test", in.arg(0))
		if fail == nil {
			c.Out.Value(v)
		}
		return fail
	})
	b.deleteLeaf(g, "Delete an outbound webhook, or one version.", func(c *app.Ctx, in input) *core.Fail {
		return objects.DeleteObject(c, objects.WebhookOutbound, deleteArgs(in), objects.Fixed("consequence_webhook_outbound"))
	})
}

func outboundUpdate(c *app.Ctx, in input) *core.Fail {
	v, fail := objects.OutboundUpdate(c, objects.OutboundUpdateArgs{
		ID: in.arg(0), URL: in.opt("url"), Events: in.list("event"), Enable: in.flag("enable"), Disable: in.flag("disable"),
	})
	if fail == nil {
		c.Out.Value(v)
	}
	return fail
}
