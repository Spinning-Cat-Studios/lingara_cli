// `lingara canvas …` (ADR 16.10.26ad D1, D2, D7). Content goes through
// `/v1/canvases`; lifecycle (delete with the typed email) through
// `/platform/objects`, after `canvas.lookup` turns the builder's id into the
// platform object's. `apply` is `canvas.Apply`'s.
package com.getlingara.cli.kotlin.commands

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.auth.call
import com.getlingara.cli.kotlin.core.Assets
import com.getlingara.cli.kotlin.core.Exit
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Json
import com.getlingara.cli.kotlin.core.items
import com.getlingara.cli.kotlin.http.Body
import com.getlingara.cli.kotlin.http.Fixed
import com.getlingara.cli.kotlin.http.Op
import com.getlingara.cli.kotlin.http.Request
import com.getlingara.cli.kotlin.http.Routes
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

object Canvas {
    val COLUMNS: Columns = listOf("ID" to "id", "NAME" to "name", "DRAFT" to "has_draft", "ACTIVE" to "active_revision", "UPDATED" to "updated_at")

    /** A `/v1/canvases` 404 while the builder flag is off (16.10.26x D3). Where no id could be wrong, say what it probably means. */
    fun <T> builderHint(body: () -> T): T = try {
        body()
    } catch (fail: Fail) {
        throw if (fail.exit == Exit.NOT_FOUND) fail.hint("the canvas builder may not be enabled for this account") else fail
    }

    fun get(ctx: Ctx, id: String): JsonNode = ctx.call(Request(Fixed.CANVAS_GET.route, listOf("id" to id)))

    fun definition(path: Path): JsonNode {
        val text = try {
            Files.readString(path, Charsets.UTF_8)
        } catch (e: IOException) {
            throw Fail.usage("$path: ${e.message ?: e.javaClass.simpleName}")
        }
        return Json.parse(text) ?: throw Fail.usage("$path is not JSON")
    }

    /** `createCanvas` answers the canvas itself, so there is no final read. */
    fun create(ctx: Ctx, name: String?, file: Path?) = builderHint {
        val def = file?.let(::definition) ?: Json.obj("schema" to 2, "nodes" to emptyList<Any>(), "edges" to emptyList<Any>())
        val body = Json.obj("definition" to def)
        if (name != null) body.put("name", name)
        ctx.out.value(ctx.call(Request(Fixed.CANVAS_CREATE.route, body = Body.JsonBody(body))))
    }

    fun list(ctx: Ctx) = builderHint { Objects.printRows(ctx, Objects.pages(ctx, Fixed.CANVAS_LIST.route), COLUMNS) }

    fun update(ctx: Ctx, id: String, name: String) =
        ctx.out.value(ctx.call(Request(Fixed.CANVAS_RENAME.route, listOf("id" to id), body = Body.JsonBody(Json.obj("name" to name)))))

    /** Saves the definition at the draft's `draft_version` (`0`: a new draft). It creates nothing else: that is `apply`'s alone (B7). */
    fun draft(ctx: Ctx, id: String, file: Path) {
        val def = definition(file)
        val draftVersion = get(ctx, id).path("draft").path("draft_version").takeIf { it.isIntegralNumber }?.asLong() ?: 0L
        val body = Json.obj("definition" to def, "draft_version" to draftVersion)
        ctx.out.value(ctx.call(Request(Fixed.CANVAS_PUT_DRAFT.route, listOf("id" to id), body = Body.JsonBody(body))))
    }

    /** The `canvas` object behind a `/v1/canvases` id (16.10.26v §8). */
    private fun lookup(ctx: Ctx, canvasId: String): String {
        val page = ctx.call(Request(Fixed.CANVAS_LOOKUP.route, query = listOf("kind" to "canvas", "runtime_id" to canvasId)))
        return page.items("items").firstOrNull()?.path("id")?.textValue() ?: throw Fail(Exit.NOT_FOUND, "object_not_found", "no canvas $canvasId")
    }

    fun delete(ctx: Ctx, args: DeleteArgs) {
        var found: String? = null
        val email = Confirm.destructive(ctx, args.confirm) { c ->
            val platformId = lookup(c, args.id).also { found = it }
            val obj = c.call(Request(Routes.named("canvas.object_get", Op.GET), listOf("id" to platformId)))
            Deletes.prompt(Kind.CANVAS, obj, args.id, args.version) { Assets.message("consequence_canvas") }
        }
        Objects.delete(ctx, Kind.CANVAS, found ?: lookup(ctx, args.id), args.version, email)
        ctx.out.nothing()
    }
}
