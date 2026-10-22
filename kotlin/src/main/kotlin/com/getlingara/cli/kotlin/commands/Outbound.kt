// Outbound webhooks (ADR 16.10.26ad D2): a `webhook_out` object (body `{url,
// event_types[]}`) under its client. A body update keeps the endpoint and
// its signing secret; enabled is object-level, never version body.
// `replace` (D1, D5) is a new endpoint with a new signing secret, then the old
// one deleted: both prompts, or the `--confirm` check, complete **before**
// the new endpoint's first write, so a decline sends neither call.
package com.getlingara.cli.kotlin.commands

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.auth.call
import com.getlingara.cli.kotlin.core.Assets
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Json
import com.getlingara.cli.kotlin.core.Made
import com.getlingara.cli.kotlin.core.items
import com.getlingara.cli.kotlin.core.str
import com.getlingara.cli.kotlin.core.strOrNull
import com.getlingara.cli.kotlin.core.strings
import com.getlingara.cli.kotlin.http.Op
import com.getlingara.cli.kotlin.http.Request
import com.getlingara.cli.kotlin.http.Routes

data class OutboundCreate(val client: String, val url: String, val event: List<String>)

data class OutboundUpdate(val id: String, val url: String? = null, val event: List<String> = emptyList(), val enable: Boolean = false, val disable: Boolean = false)

data class OutboundReplace(val id: String, val url: String?, val event: List<String>, val confirm: ConfirmArgs)

object Outbound {
    private val K = Kind.WEBHOOK_OUTBOUND
    val COLUMNS: Columns = listOf("ID" to "id", "CLIENT" to "parent_id", "URL" to "url", "EVENTS" to "event_types", "ENABLED" to "enabled")
    val consequence: Consequence = { Assets.message("consequence_webhook_outbound") }

    /** Create, publish, mint the signing secret (revealed once), read. */
    fun create(ctx: Ctx, args: OutboundCreate): JsonNode {
        // The verb takes no name, so the object is named by its URL, cut to the 80 characters a platform object name may hold (16.10.26v).
        val name = args.url.codePoints().limit(80).toArray().let { String(it, 0, it.size) }
        val made = Creation(ctx)
        return made.conclude {
            val id = made.obj(K, name, args.client, Json.obj("url" to args.url, "event_types" to args.event)).str("id")
            made.mintSecret(K, id)
            K to id
        }
    }

    /** `enable`, `disable` or `test`: object-level, never versioned. */
    fun action(ctx: Ctx, op: Op, id: String): JsonNode = ctx.call(Request(Routes.objectRoute(K.word, op), listOf("id" to id)))

    /** `--url`/`--event` are a body update; `--enable`/`--disable` follow it. */
    fun update(ctx: Ctx, args: OutboundUpdate): JsonNode {
        var last: JsonNode? = null
        if (args.url != null || args.event.isNotEmpty()) {
            last = Objects.bodyUpdate(ctx, K, args.id) { body ->
                args.url?.let { body.put("url", it) }
                if (args.event.isNotEmpty()) body.set<JsonNode>("event_types", Json.of(args.event))
            }
        }
        if (args.enable || args.disable) last = action(ctx, if (args.enable) Op.ENABLE else Op.DISABLE, args.id)
        return last ?: throw Fail.usage("nothing to update: pass --url, --event, --enable or --disable")
    }

    /** The old object and its serving version's body: the lookups the new endpoint's client and any omitted flag come from. */
    private fun lookups(ctx: Ctx, id: String): Pair<JsonNode, JsonNode> {
        val old = Objects.get(ctx, K, id)
        val semver = old.strOrNull("serving_version") ?: old.items("versions").firstOrNull()?.strOrNull("semver") ?: ""
        return old to Objects.versionOp(ctx, K, Op.VERSION_GET, id, semver).path("body")
    }

    fun replace(ctx: Ctx, args: OutboundReplace) {
        var looked: Pair<JsonNode, JsonNode>? = null
        val email = Confirm.destructive(ctx, args.confirm) { c ->
            lookups(c, args.id).also { looked = it }.let { (old, _) -> Deletes.prompt(K, old, args.id, null) { Assets.message("consequence_replace") } }
        }
        val (old, body) = looked ?: lookups(ctx, args.id)
        val create = OutboundCreate(
            client = old.str("parent_id"),
            url = args.url ?: body.str("url"),
            event = args.event.ifEmpty { body.path("event_types").strings() },
        )
        val new = create(ctx, create)
        try {
            Objects.delete(ctx, K, args.id, null, email)
        } catch (fail: Fail) {
            fail.made = listOf(Made("webhook_outbound", new.str("id")))
            throw fail.hint("the old endpoint ${args.id} still delivers; delete it with `lingara webhook outbound delete ${args.id}`")
        }
    }
}
