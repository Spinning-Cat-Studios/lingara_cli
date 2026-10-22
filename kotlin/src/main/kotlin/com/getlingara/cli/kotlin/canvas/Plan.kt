// D7 step 2: the web planner's rules (`plan.ts`) over field nodes.
//
// Ops come out in topological order and every client reference is a backward
// `{handle}`. "Still live" means live **and** still on the recorded client:
// a recorded client is live when the client list has a row with its
// `runtime_id`; a webhook or app when its list has its `runtime_id` under that
// client's platform object; a secret when its client is (the platform lists
// carry no secrets, and a recorded secret is never re-planned). A node with
// `custom: true` runs a script, which only the browser's sandbox may do, so
// it refuses the plan by its handle.
package com.getlingara.cli.kotlin.canvas

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.apps.ManifestRules
import com.getlingara.cli.kotlin.core.Json
import com.getlingara.cli.kotlin.core.str

/** Node handle → the runtime id an earlier apply recorded for it. */
typealias Records = MutableMap<String, String>

/** One `/platform/objects` list row, as the planner joins it. */
data class Row(val id: String, val runtimeId: String, val parentId: String) {
    companion object {
        fun read(v: JsonNode) = Row(v.str("id"), v.str("runtime_id"), v.str("parent_id"))
    }
}

/** The live `oauth_client`, `webhook_out` and `app` lists (D7 step 1). */
data class Live(val clients: List<Row>, val webhooks: List<Row>, val apps: List<Row>) {
    fun clientByRuntime(runtimeId: String): Row? = clients.firstOrNull { it.runtimeId == runtimeId }

    /** The platform object id behind a recorded runtime id of [kind]. */
    fun platformId(kind: String, runtimeId: String): String? {
        val rows = when (kind) {
            "client" -> clients
            "webhook" -> webhooks
            else -> apps
        }
        return rows.firstOrNull { it.runtimeId == runtimeId }?.id
    }
}

/** `{handle, kind, op, client?: {handle}, id?, body?}`, the dry-run's shape. */
data class PlanOp(val handle: String, val kind: String, val op: String, val client: String?, val id: String?, val body: JsonNode?) {
    fun wire(): JsonNode {
        val v = Json.obj("handle" to handle, "kind" to kind, "op" to op)
        client?.let { v.set<JsonNode>("client", Json.obj("handle" to it)) }
        id?.let { v.put("id", it) }
        body?.let { v.set<JsonNode>("body", it) }
        return v
    }
}

/** Why a plan cannot be made. */
sealed class Refusal(val handle: String?) : Exception() {
    class Script(handle: String) : Refusal(handle)

    class Fields(handle: String, val why: String) : Refusal(handle)

    class NoClient(handle: String) : Refusal(handle)

    object Cycle : Refusal(null)

    /** An app node whose manifest's render URL is a quick tunnel or `dev.invalid` (16.10.26ad D7 step 2, 16.10.26ae D10 row 15). */
    class Ephemeral(handle: String) : Refusal(handle)
}

object Plan {
    /** The recorded entries still live by the rule above. */
    fun stillLive(def: Definition, recorded: Map<String, String>, live: Live): Records {
        val kept = linkedMapOf<String, String>()
        for (node in def.topoOrder().orEmpty()) {
            val record = recorded[node.id] ?: continue
            val keep = if (node.kind == "client") {
                live.clientByRuntime(record) != null
            } else {
                val client = def.clientOf(node.id)?.let { kept[it] }?.let(live::clientByRuntime)
                client != null && dependantLive(node, record, client.id, live)
            }
            if (keep) kept[node.id] = record
        }
        return kept.toSortedMap()
    }

    private fun dependantLive(node: Node, record: String, clientPlatformId: String, live: Live): Boolean {
        val onClient = { rows: List<Row> -> rows.any { it.runtimeId == record && it.parentId == clientPlatformId } }
        return when (node.kind) {
            "secret" -> true
            "webhook" -> onClient(live.webhooks)
            else -> onClient(live.apps)
        }
    }

    private fun bodyFor(node: Node, create: Boolean): JsonNode? {
        val body = try {
            when (node.kind) {
                "client" -> Spec.client(node.fields, create)
                "webhook" -> Spec.webhook(node.fields)
                "app" -> Spec.app(node.fields)
                else -> return null
            }
        } catch (e: Invalid) {
            throw Refusal.Fields(node.id, e.message.orEmpty())
        }
        if (node.kind == "app" && ManifestRules.ephemeral(body.path("manifest").str("render_url"))) throw Refusal.Ephemeral(node.id)
        return body
    }

    /** D7 step 2: every field node's op, in plan order, over the kept records. */
    fun build(def: Definition, kept: Map<String, String>): List<PlanOp> {
        val order = def.topoOrder() ?: throw Refusal.Cycle
        order.firstOrNull { it.custom }?.let { throw Refusal.Script(it.id) }
        val ops = mutableListOf<PlanOp>()
        for (node in order) {
            val record = kept[node.id]
            if (record != null && node.kind == "secret") continue
            val client = if (node.kind == "client") null else def.clientOf(node.id) ?: throw Refusal.NoClient(node.id)
            val op = if (record == null) "create" else "update"
            ops.add(PlanOp(node.id, node.kind, op, client.takeIf { op == "create" }, record, bodyFor(node, op == "create")))
        }
        return ops
    }
}
