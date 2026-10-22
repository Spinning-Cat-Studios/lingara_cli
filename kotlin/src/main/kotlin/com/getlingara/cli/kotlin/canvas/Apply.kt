// Canvas apply, matryoshka-style (ADR 16.10.26ad D7; contract B7): a draft
// creates nothing; `apply` plans in the web planner's order, dry-runs the
// plan, then runs **this CLI's own verbs** in-process, one per op. Here are
// steps 1–3 and the two flags that stop there: `--dry-run` prints the
// verdicts, `--print-commands` the argvs. Neither writes. Steps 4–7 are
// `Execute`'s. `Plan` and `Argv` are pure, and both are pinned by
// `conformance/vectors/canvas-plans.json`, which every kit's tests read.
package com.getlingara.cli.kotlin.canvas

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.auth.call
import com.getlingara.cli.kotlin.commands.Canvas
import com.getlingara.cli.kotlin.commands.Columns
import com.getlingara.cli.kotlin.commands.Kind
import com.getlingara.cli.kotlin.commands.Objects
import com.getlingara.cli.kotlin.core.Exit
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Json
import com.getlingara.cli.kotlin.core.items
import com.getlingara.cli.kotlin.http.Body
import com.getlingara.cli.kotlin.http.Fixed
import com.getlingara.cli.kotlin.http.Request
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private val VERDICT_COLUMNS: Columns = listOf("HANDLE" to "handle", "KIND" to "kind", "OP" to "op", "VERDICT" to "verdict", "CODE" to "code")

/** What one apply works over: the draft, its record and the live lists. */
class Planned(val canvasId: String, val draftVersion: Long, val kept: Map<String, String>, val live: Live, val ops: List<PlanOp>)

/** `canvas apply`'s two stopping flags. */
data class ApplyArgs(val id: String, val dryRun: Boolean, val printCommands: Boolean)

object Apply {
    private fun refused(text: String) = Fail(Exit.REFUSED, "canvas_apply_refused", text)

    /** The active record overlaid by the draft's, kept to the draft's nodes (`recordedObjects`). */
    private fun recorded(canvas: JsonNode, def: Definition): Map<String, String> {
        val all = sortedMapOf<String, String>()
        for (side in listOf("active", "draft")) {
            canvas.path(side).path("objects").properties().forEach { (h, v) -> all[h] = v.path("id").asText("") }
        }
        return all.filterKeys { h -> def.nodes.any { it.id == h } }
    }

    private fun refusalFail(r: Refusal): Fail = when (r) {
        is Refusal.Ephemeral -> Fail.message(Exit.RENDER_URL_EPHEMERAL, "render_url_ephemeral", "file" to "node ${r.handle}'s manifest")
        is Refusal.Script -> refused("node ${r.handle} runs a script; apply this canvas in the browser")
        is Refusal.Fields -> refused("node ${r.handle}'s fields are not one the builder can apply: ${r.why}")
        is Refusal.NoClient -> refused("node ${r.handle} needs exactly one client node upstream")
        Refusal.Cycle -> refused("the canvas's edges form a cycle")
    }

    /** D7 steps 1 and 2: the canvas and the three lists, every page, then the plan. */
    fun plan(ctx: Ctx, id: String): Planned {
        val canvas = Canvas.get(ctx, id)
        if (canvas.path("draft").isNull || canvas.path("draft").isMissingNode) throw refused("nothing to apply: save a draft with `lingara canvas draft`")
        val rows = { k: Kind -> Objects.list(ctx, k).map(Row::read) }
        val live = Live(rows(Kind.OAUTH_CLIENT), rows(Kind.WEBHOOK_OUTBOUND), rows(Kind.APP))
        val definition = Definition.read(canvas.path("draft").path("definition"))
        val kept = Plan.stillLive(definition, recorded(canvas, definition), live)
        val ops = try {
            Plan.build(definition, kept)
        } catch (r: Refusal) {
            throw refusalFail(r)
        }
        val draftVersion = canvas.path("draft").path("draft_version").takeIf { it.isIntegralNumber }?.asLong() ?: 0L
        return Planned(id, draftVersion, kept, live, ops)
    }

    /** D7 step 3: zero writes, `canvas:read`. */
    private fun dryRun(ctx: Ctx, ops: List<PlanOp>): JsonNode =
        ctx.call(Request(Fixed.CANVAS_DRY_RUN.route, body = Body.JsonBody(Json.obj("ops" to ops.map(PlanOp::wire)))))

    private fun stopFail(stop: Stop): Fail {
        if (stop.replace != null) return refused("node ${stop.replace}: unexpected `would_replace` from the server; nothing was applied")
        val fail = refused("the dry-run refused ${stop.refused.size} op(s); nothing was applied")
        stop.refused.forEach { (h, code, error) -> fail.hint("$h: $error ($code)") }
        return fail
    }

    fun run(ctx: Ctx, args: ApplyArgs) {
        val planned = plan(ctx, args.id)
        val answer = dryRun(ctx, planned.ops)
        val verdicts = answer.items("verdicts")
        if (args.dryRun) {
            if (ctx.out.json) ctx.out.jsonValue(answer) else Objects.printRows(ctx, verdicts, VERDICT_COLUMNS)
            if (answer.path("ok").isBoolean && !answer.path("ok").booleanValue()) throw refused("the dry-run refused at least one op")
            return
        }
        val steps = try {
            Argv.steps(planned.ops, verdicts, planned.live, planned.kept)
        } catch (stop: Stop) {
            throw stopFail(stop)
        }
        val manifests = writeManifests(planned.ops)
        try {
            if (args.printCommands) printCommands(ctx, steps, manifests) else Execute.run(ctx, planned, steps, manifests)
        } finally {
            if (!args.printCommands) manifests.values.forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    /** Each app op's manifest, as the file its `--manifest` names. */
    private fun writeManifests(ops: List<PlanOp>): Map<String, Path> = ops.filter { it.kind == "app" }.associate { op ->
        val path = Path.of(System.getProperty("java.io.tmpdir"), "lingara-${ProcessHandle.current().pid()}-${op.handle}.manifest.json")
        try {
            Files.writeString(path, Json.write(op.body?.path("manifest") ?: Json.of(null)), Charsets.UTF_8)
        } catch (e: IOException) {
            throw Fail.failure("$path: ${e.message}")
        }
        op.handle to path
    }

    /** The matryoshka made visible: what apply would run, as shell lines. */
    private fun printCommands(ctx: Ctx, steps: List<Step>, manifests: Map<String, Path>) {
        val lines = steps.filter { it.argv.isNotEmpty() }.map { s -> Argv.shellLine(s.argv) { h -> manifests[h]?.toString().orEmpty() } }
        if (ctx.out.json) ctx.out.jsonValue(Json.of(lines)) else lines.forEach { ctx.out.print("$it\n") }
    }
}
