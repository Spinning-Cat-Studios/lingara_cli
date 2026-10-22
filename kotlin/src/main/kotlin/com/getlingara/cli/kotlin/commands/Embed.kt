// `lingara embed …` and `lingara webhook inbound …` (ADR 16.10.26ad D2): an
// object on a confidential client of its own, made client first. An embed
// (body `{origins[]}`) sits on a metered client, which holds `embed:mint`
// through the embed's projection; an inbound webhook (body
// `{event_types[]}`) is the credential a sender uses for `POST /v1/events`,
// and `events:write` comes from its projection. Neither client's own body
// names the scope, so deleting the child clears it (16.10.26v §1, §5).
package com.getlingara.cli.kotlin.commands

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Json
import com.getlingara.cli.kotlin.core.str

/** A rename and one body member a list replaces, as `embed update` and `webhook inbound update` take them. */
data class ChildUpdate(val id: String, val name: String?, val values: List<String>)

object Embed {
    val COLUMNS: Columns = listOf("ID" to "id", "NAME" to "name", "CLIENT" to "parent_id", "ORIGINS" to "origins", "SERVING" to "serving_version")
    val INBOUND_COLUMNS: Columns = listOf("ID" to "id", "NAME" to "name", "CLIENT" to "parent_id", "EVENTS" to "event_types")

    /** The client first, its secret, then the child under it; the child's read is printed. */
    private fun withClient(ctx: Ctx, name: String, clientBody: JsonNode, child: Kind, childBody: JsonNode): JsonNode {
        val made = Creation(ctx)
        return made.conclude {
            val clientId = made.obj(Kind.OAUTH_CLIENT, name, null, clientBody).str("id")
            made.mintSecret(Kind.OAUTH_CLIENT, clientId)
            child to made.obj(child, name, clientId, childBody).str("id")
        }
    }

    fun create(ctx: Ctx, name: String, origins: List<String>, scopes: List<String>) {
        val client = Json.obj("client_type" to "confidential", "billing_mode" to "metered", "allowed_scopes" to scopes)
        withClient(ctx, name, client, Kind.EMBED, Json.obj("origins" to origins))
    }

    /** No `--event` omits `event_types`, which 16.10.26v §5 projects as every inbound type. */
    fun createInbound(ctx: Ctx, name: String, events: List<String>) {
        val body = if (events.isEmpty()) Json.obj() else Json.obj("event_types" to events)
        val hook = withClient(ctx, name, Json.obj("client_type" to "confidential", "allowed_scopes" to emptyList<String>()), Kind.WEBHOOK_INBOUND, body)
        val ingest = ctx.app.http.bases.api.trimEnd('/') + "/v1/events"
        val clientId = hook.path("client_id").textValue() ?: "-"
        ctx.out.note("Senders POST to $ingest as client $clientId, with its secret and an Idempotency-Key header.")
    }

    /** `--name` renames; the list flag replaces [member] in a body update. */
    fun update(ctx: Ctx, k: Kind, args: ChildUpdate, member: String) {
        var last: JsonNode? = args.name?.let { Objects.rename(ctx, k, args.id, it) }
        if (args.values.isNotEmpty()) last = Objects.bodyUpdate(ctx, k, args.id) { it.set<JsonNode>(member, Json.of(args.values)) }
        val flag = if (k == Kind.EMBED) "--origin" else "--event"
        ctx.out.value(last ?: throw Fail.usage("nothing to update: pass --name or $flag"))
    }
}
