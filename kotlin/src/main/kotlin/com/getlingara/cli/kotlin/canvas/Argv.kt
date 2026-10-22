// D7 steps 3–4: the dry-run's verdicts over the plan become this CLI's own
// command lines. A `refused` verdict stops apply with every refusal named; a
// `would_replace`, which the token door no longer sends, stops it before any
// write. A `would_update` webhook carries exactly the flags its `changes`
// name: `--url`/`--event` (a new version that keeps the endpoint and its
// secret) and `--enable` (no builder spec disables one).
package com.getlingara.cli.kotlin.canvas

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.core.Json
import com.getlingara.cli.kotlin.core.str
import com.getlingara.cli.kotlin.core.strings

/** An argv word: text, an id already known (a platform object id), one an earlier step of this run makes, or an app op's manifest file. */
sealed interface Part {
    data class Text(val text: String) : Part

    data class Id(val id: String) : Part

    data class Made(val handle: String) : Part

    data class Manifest(val handle: String) : Part
}

/** What apply records for a step's node (16.10.26x D4: runtime ids only). */
sealed interface Recording {
    object RuntimeId : Recording

    /** `minted_with_client`: the secret its client's create minted. */
    data class ClientSecret(val client: String) : Recording

    object SecretId : Recording

    object Unchanged : Recording
}

/** One step; an empty [argv] makes no call. */
data class Step(val handle: String, val kind: String, val action: String, val argv: List<Part>, val records: Recording)

/** Why apply stops before writing anything: every refused op (handle, code, error), or an unexpected replace. */
class Stop(val refused: List<Triple<String, String, String>>, val replace: String?) : Exception()

private fun text(s: String) = Part.Text(s)

private fun repeated(flag: String, values: List<String>) = values.flatMap { listOf(text(flag), text(it)) }

object Argv {
    /** Every step, or why apply must stop before writing anything. */
    fun steps(ops: List<PlanOp>, verdicts: List<JsonNode>, live: Live, kept: Map<String, String>): List<Step> {
        val refused = verdicts.filter { it.str("verdict") == "refused" }.map { Triple(it.str("handle"), it.str("code"), it.str("error")) }
        if (refused.isNotEmpty()) throw Stop(refused, null)
        verdicts.firstOrNull { it.str("verdict") == "would_replace" }?.let { throw Stop(emptyList(), it.str("handle")) }
        val verdictOf = { h: String -> verdicts.firstOrNull { it.str("handle") == h } }
        return ops.map { op -> step(op, verdictOf(op.handle), Context(live, kept, ops)) }
    }

    /** What every step reads besides its op and verdict. */
    private class Context(val live: Live, val kept: Map<String, String>, val ops: List<PlanOp>) {
        /** The client an op points at: made in this run, or a recorded one's platform object id. */
        fun clientRef(handle: String): Part {
            val madeHere = ops.any { it.handle == handle && it.op == "create" }
            val known = kept[handle]?.let { live.platformId("client", it) }
            return if (!madeHere && known != null) Part.Id(known) else Part.Made(handle)
        }
    }

    /** One op's step: its argv and what it records, by kind. */
    private fun step(op: PlanOp, verdict: JsonNode?, c: Context): Step {
        val changes = verdict?.path("changes")?.strings().orEmpty()
        val unchanged = op.op == "update" && changes.isEmpty()
        val action = if (unchanged) "unchanged" else op.op
        if (unchanged) return Step(op.handle, op.kind, action, emptyList(), Recording.Unchanged)
        val own = op.id?.let { c.live.platformId(op.kind, it) }?.let { Part.Id(it) } ?: Part.Made(op.handle)
        val made = StepInput(op, op.body ?: Json.obj(), own, op.client?.let(c::clientRef) ?: Part.Made(""), changes, verdict)
        val (argv, records) = (BY_KIND[op.kind] ?: ::appStep)(made)
        return Step(op.handle, op.kind, action, argv, records)
    }

    /** What a kind's step is built from. */
    private class StepInput(val op: PlanOp, val body: JsonNode, val own: Part, val client: Part, val changes: List<String>, val verdict: JsonNode?) {
        val create: Boolean get() = op.op == "create"
    }

    private val BY_KIND: Map<String, (StepInput) -> Pair<List<Part>, Recording>> = mapOf(
        "client" to { m -> clientArgv(m.body, m.create, m.own) to if (m.create) Recording.RuntimeId else Recording.Unchanged },
        "secret" to ::secretStep,
        "webhook" to { m -> if (m.create) webhookCreate(m.body, m.client) to Recording.RuntimeId else webhookUpdate(m.body, m.changes, m.own) to Recording.Unchanged },
    )

    /** A secret its client's create already minted makes no call; any other is `oauth_client secret create`. */
    private fun secretStep(m: StepInput): Pair<List<Part>, Recording> =
        if (m.verdict?.str("note") == "minted_with_client") {
            emptyList<Part>() to Recording.ClientSecret(m.op.client.orEmpty())
        } else {
            listOf(text("oauth_client"), text("secret"), text("create"), m.client) to Recording.SecretId
        }

    private fun appStep(m: StepInput): Pair<List<Part>, Recording> =
        if (m.create) {
            appCreate(m.op, m.body, m.client) to Recording.RuntimeId
        } else {
            listOf(text("apps"), text("update"), m.own, text("--manifest"), Part.Manifest(m.op.handle)) to Recording.Unchanged
        }

    private fun clientArgv(body: JsonNode, create: Boolean, own: Part): List<Part> {
        val head = if (create) listOf(text("oauth_client"), text("create")) else listOf(text("oauth_client"), text("update"), own)
        return head + listOf(text("--name"), text(body.str("name"))) +
            repeated("--scope", body.path("scopes").strings()) + repeated("--redirect-uri", body.path("redirect_uris").strings())
    }

    private fun webhookCreate(body: JsonNode, client: Part): List<Part> =
        listOf(text("webhook"), text("outbound"), text("create"), text("--client"), client, text("--url"), text(body.str("url"))) +
            repeated("--event", body.path("event_types").strings())

    private fun webhookUpdate(body: JsonNode, changes: List<String>, own: Part): List<Part> {
        val argv = mutableListOf(text("webhook"), text("outbound"), text("update"), own)
        if ("url" in changes) argv += listOf(text("--url"), text(body.str("url")))
        if ("event_types" in changes) argv += repeated("--event", body.path("event_types").strings())
        if ("enabled" in changes) argv += text("--enable")
        return argv
    }

    private fun appCreate(op: PlanOp, body: JsonNode, client: Part): List<Part> =
        listOf(text("apps"), text("create"), text(Spec.appName(body, op.handle)), text("--client"), client, text("--manifest"), Part.Manifest(op.handle))

    /** One argv as a shell line, ids not yet made as `<handle>`. */
    fun shellLine(argv: List<Part>, manifest: (String) -> String): String = "lingara " + argv.joinToString(" ") { p ->
        when (p) {
            is Part.Text -> quote(p.text)
            is Part.Id -> quote(p.id)
            is Part.Made -> quote("<${p.handle}>")
            is Part.Manifest -> quote(manifest(p.handle))
        }
    }

    /** POSIX single quotes, only where a word needs them. */
    fun quote(word: String): String {
        val plain = word.isNotEmpty() && word.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-_./:@=,+" }
        return if (plain) word else "'" + word.replace("'", "'\\''") + "'"
    }
}
