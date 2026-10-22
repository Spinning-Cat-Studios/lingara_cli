// D7 steps 4–7. Each step's argv is dispatched **in-process** through
// `App.run`, `main`'s own entry point, with `--json`, so no credential
// crosses a process boundary and no second token exchange happens. Apply
// records each object's `runtime_id` (a secret's id), never its platform
// object id, then activates `complete`, or `partial` at the first failure.
package com.getlingara.cli.kotlin.canvas

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.auth.call
import com.getlingara.cli.kotlin.commands.Kind
import com.getlingara.cli.kotlin.commands.Objects
import com.getlingara.cli.kotlin.core.Assets
import com.getlingara.cli.kotlin.core.Exit
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Json
import com.getlingara.cli.kotlin.core.str
import com.getlingara.cli.kotlin.core.strOrNull
import com.getlingara.cli.kotlin.http.Body
import com.getlingara.cli.kotlin.http.Fixed
import com.getlingara.cli.kotlin.http.Request
import com.getlingara.cli.kotlin.output.Table
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Path

/** A `runtime_id` still `null` is re-read once a second, ten times (D7). */
private const val PUBLISH_POLLS = 10

private fun kindOf(nodeKind: String): Kind = when (nodeKind) {
    "client" -> Kind.OAUTH_CLIENT
    "webhook" -> Kind.WEBHOOK_OUTBOUND
    else -> Kind.APP
}

private fun objectsOf(recorded: Map<String, String>): ObjectNode = Json.obj().also { o -> recorded.forEach { (h, id) -> o.set<JsonNode>(h, Json.obj("id" to id)) } }

/** One run's progress: what it recorded, made, minted and will report. */
private class State(kept: Map<String, String>, val manifests: Map<String, Path>) {
    val recorded = kept.toSortedMap()

    /** Handle → the platform object id an earlier step made. */
    val made = mutableMapOf<String, String>()

    /** Handle → the secret its client's create minted. */
    val minted = mutableMapOf<String, String>()
    val secrets = mutableListOf<ObjectNode>()
    val rows = mutableListOf<List<String>>()

    fun resolve(argv: List<Part>): List<String> = argv.map { p ->
        when (p) {
            is Part.Text -> p.text
            is Part.Id -> p.id
            is Part.Made -> made[p.handle] ?: throw Fail.failure("${p.handle} was not made")
            is Part.Manifest -> manifests[p.handle]?.toString() ?: throw Fail.failure("no manifest for ${p.handle}")
        }
    }

    /** One inner verb; its JSON, any secret it revealed kept even when it failed part-way. */
    fun invoke(ctx: Ctx, handle: String, argv: List<String>): JsonNode {
        val outcome = ctx.app.run(argv + listOf("--json", "--profile", ctx.profile), PrintStream(OutputStream.nullOutputStream()))
        val answer = Json.parse(outcome.stdout) ?: Json.of(null)
        val secret = when {
            answer.path("secret").isObject -> Json.obj("handle" to handle, "id" to answer.path("secret").path("id"), "value" to answer.path("secret").path("value"))
            answer.strOrNull("secret_id") != null -> Json.obj("handle" to handle, "id" to answer.path("secret_id"), "value" to answer.path("secret"))
            else -> null
        }
        secret?.let {
            minted[handle] = it.str("id")
            secrets.add(it)
        }
        return outcome.fail?.let { throw it } ?: answer
    }

    fun step(ctx: Ctx, step: Step) {
        val records = step.records
        when {
            records is Recording.ClientSecret -> {
                val id = minted[records.client] ?: throw Fail.failure("${records.client} minted no secret")
                secrets.filter { it.str("handle") == records.client }.forEach { it.put("handle", step.handle) }
                recorded[step.handle] = id
            }
            step.argv.isEmpty() -> {}
            else -> record(ctx, step, invoke(ctx, step.handle, resolve(step.argv)))
        }
        rows.add(listOf(step.handle, step.kind, step.action, recorded[step.handle] ?: "-"))
    }

    private fun record(ctx: Ctx, step: Step, answer: JsonNode) {
        when (step.records) {
            Recording.RuntimeId -> {
                val platformId = answer.str("id")
                made[step.handle] = platformId
                recorded[step.handle] = runtimeId(ctx, kindOf(step.kind), platformId, answer)
            }
            Recording.SecretId -> recorded[step.handle] = answer.str("secret_id")
            else -> {}
        }
    }

    /** D7 step 7: `HANDLE  KIND  ACTION  ID`, then every new secret, once. */
    fun report(ctx: Ctx, status: String) {
        if (ctx.out.json) {
            ctx.out.jsonValue(Json.obj("objects" to objectsOf(recorded), "secrets" to Json.of(secrets), "status" to status))
        } else {
            var text = Table.render(listOf("HANDLE", "KIND", "ACTION", "ID"), rows)
            if (secrets.isNotEmpty()) {
                val secretRows = secrets.map { s -> listOf("handle", "id", "value").map { s.path(it).textValue() ?: "-" } }
                text += "\n" + Table.render(listOf("HANDLE", "SECRET_ID", "SECRET"), secretRows)
            }
            ctx.out.print(text)
        }
        if (secrets.isNotEmpty()) ctx.out.note("Each secret is shown once: store them now. They cannot be read again.")
    }
}

/** The object's `runtime_id`, set once publishing has projected (16.10.26v §5). Still `null` after ten reads: apply stops, naming it. */
private fun runtimeId(ctx: Ctx, k: Kind, platformId: String, answer: JsonNode): String {
    var current = answer
    repeat(PUBLISH_POLLS) {
        current.strOrNull("runtime_id")?.let { return it }
        ctx.app.http.clock.sleep(1)
        current = Objects.get(ctx, k, platformId)
    }
    return current.strOrNull("runtime_id")
        ?: throw Fail(Exit.UNAVAILABLE, "object_projection_pending", "${k.word} $platformId is still publishing; nothing after it was applied")
}

/** `{draft_version, outcome, objects}`: runtime ids only (16.10.26x D4). */
private fun activate(ctx: Ctx, planned: Planned, recorded: Map<String, String>, outcome: String) {
    val body = Json.obj("draft_version" to planned.draftVersion, "outcome" to outcome, "objects" to objectsOf(recorded))
    try {
        ctx.call(Request(Fixed.CANVAS_ACTIVATE.route, listOf("id" to planned.canvasId), body = Body.JsonBody(body)))
    } catch (fail: Fail) {
        if (fail.code == "canvas_conflict") fail.hint("the canvas changed while applying; run `lingara canvas get ${planned.canvasId}`")
        throw fail
    }
}

object Execute {
    fun run(ctx: Ctx, planned: Planned, steps: List<Step>, manifests: Map<String, Path>) {
        val state = State(planned.kept, manifests)
        steps.forEachIndexed { done, step ->
            try {
                state.step(ctx, step)
            } catch (fail: Fail) {
                throw partial(ctx, planned, state, "$done" to "${steps.size}", fail)
            }
        }
        activate(ctx, planned, state.recorded, "complete")
        state.report(ctx, "complete")
    }

    private fun partial(ctx: Ctx, planned: Planned, state: State, progress: Pair<String, String>, fail: Fail): Fail {
        val notSaved = runCatching { activate(ctx, planned, state.recorded, "partial") }.exceptionOrNull() as? Fail
        state.report(ctx, "partial")
        notSaved?.let { fail.hint("the partial record was not saved: ${it.text} (${it.code})") }
        return fail.hint(Assets.message("canvas_apply_partial", "done" to progress.first, "total" to progress.second, "id" to planned.canvasId))
    }
}
