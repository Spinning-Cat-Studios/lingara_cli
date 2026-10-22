// `lingara webhook inbound|outbound …` (ADR 16.10.26ad D1), the shape the
// release plan asked for, plus `replace` and `test` on outbound.
package com.getlingara.cli.kotlin.cli

import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.commands.Deletes
import com.getlingara.cli.kotlin.commands.Embed
import com.getlingara.cli.kotlin.commands.Kind
import com.getlingara.cli.kotlin.commands.Outbound
import com.getlingara.cli.kotlin.commands.OutboundCreate
import com.getlingara.cli.kotlin.commands.OutboundReplace
import com.getlingara.cli.kotlin.commands.OutboundUpdate
import com.getlingara.cli.kotlin.http.Op

class InboundCreate : Leaf("create", "Create an inbound webhook and its client, and show the client's secret once.") {
    private val name by need("name", "The webhook's name")
    private val event by many("event", "An inbound event type it accepts; absent, every one", "type")

    override fun execute(ctx: Ctx) = Embed.createInbound(ctx, name, event)
}

class OutboundCreateLeaf : Leaf("create", "Create an outbound webhook, and show its signing secret once.") {
    private val client by need("client", "The OAuth client it belongs to", "id")
    private val url by need("url", "The endpoint deliveries go to", "url")
    private val event by needMany("event", "An event type to deliver", "type")

    override fun execute(ctx: Ctx) {
        Outbound.create(ctx, OutboundCreate(client, url, event))
    }
}

class OutboundUpdateLeaf : Leaf(
    "update",
    "Change an outbound webhook's URL or event types, keeping its signing secret, or enable or disable it.",
) {
    private val id by arg("id")
    private val url by opt("url", "The new endpoint", "url")
    private val event by many("event", "Replaces the event types", "type")
    private val enable by switch("enable", "Resume deliveries")
    private val disable by switch("disable", "Pause deliveries")

    override fun given() = setOfNotNull("enable".takeIf { enable }, "disable".takeIf { disable })

    override fun execute(ctx: Ctx) = ctx.out.value(Outbound.update(ctx, OutboundUpdate(id, url, event, enable, disable)))
}

class OutboundReplaceLeaf : Dangerous("replace", "Start again: a new endpoint with a new signing secret, then the old one deleted.") {
    private val id by arg("id")
    private val url by opt("url", "The new endpoint; absent, the old one's", "url")
    private val event by many("event", "The new event types; absent, the old one's", "type")

    override fun execute(ctx: Ctx) = Outbound.replace(ctx, OutboundReplace(id, url, event, confirm))
}

class OutboundTest : Leaf("test", "Send a test delivery.") {
    private val id by arg("id")

    override fun execute(ctx: Ctx) = ctx.out.value(Outbound.action(ctx, Op.TEST, id))
}

object WebhookTrees {
    fun inbound(): Topic = Topic(
        "inbound",
        "Inbound webhooks: a confidential client a sender uses for POST /v1/events.",
        InboundCreate(),
        ListLeaf(Kind.WEBHOOK_INBOUND, Embed.INBOUND_COLUMNS, "List your inbound webhooks."),
        GetLeaf(Kind.WEBHOOK_INBOUND, "Show one inbound webhook."),
        ChildUpdateLeaf(Kind.WEBHOOK_INBOUND, "Rename an inbound webhook, or replace its event types.", "event", "Replaces the event types", "event_types"),
        DeleteLeaf(Kind.WEBHOOK_INBOUND, "Delete an inbound webhook, or one version. Its client stays.", Deletes.naming("consequence_webhook_inbound")),
    )

    fun outbound(): Topic = Topic(
        "outbound",
        "Outbound webhooks: events Lingara delivers to your endpoint, signed.",
        OutboundCreateLeaf(),
        ListLeaf(Kind.WEBHOOK_OUTBOUND, Outbound.COLUMNS, "List your outbound webhooks."),
        GetLeaf(Kind.WEBHOOK_OUTBOUND, "Show one outbound webhook."),
        OutboundUpdateLeaf(),
        OutboundReplaceLeaf(),
        OutboundTest(),
        DeleteLeaf(Kind.WEBHOOK_OUTBOUND, "Delete an outbound webhook, or one version.", Outbound.consequence),
    )
}
