// The `/platform/objects` family every kind shares (16.10.26v §8; ADR
// 16.10.26ad D2): list every page, read, rename, create and publish, the body
// update that respects the one editable version, delete with the typed
// email, and the one-time secret mint.
package com.getlingara.cli.kotlin.commands

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.auth.call
import com.getlingara.cli.kotlin.core.Json
import com.getlingara.cli.kotlin.core.items
import com.getlingara.cli.kotlin.core.str
import com.getlingara.cli.kotlin.core.strOrNull
import com.getlingara.cli.kotlin.http.Body
import com.getlingara.cli.kotlin.http.Op
import com.getlingara.cli.kotlin.http.Request
import com.getlingara.cli.kotlin.http.Route
import com.getlingara.cli.kotlin.http.Routes
import com.getlingara.cli.kotlin.output.Table

/** A CLI word, the 16.10.26v kind behind it, and `{kind}` in `confirm_yes_no`. */
data class Kind(val word: String, val kind: String, val label: String) {
    companion object {
        val OAUTH_CLIENT = Kind("oauth_client", "oauth_client", "OAuth client")
        val APP = Kind("app", "app", "app")
        val EMBED = Kind("embed", "embed", "embed")
        val WEBHOOK_INBOUND = Kind("webhook_inbound", "webhook_in", "inbound webhook")
        val WEBHOOK_OUTBOUND = Kind("webhook_outbound", "webhook_out", "outbound webhook")
        val CANVAS = Kind("canvas", "canvas", "canvas")
    }
}

/** A list's human columns: each a heading and the member it reads. */
typealias Columns = List<Pair<String, String>>

object Objects {
    private fun Ctx.send(route: Route, vars: List<Pair<String, String>>, body: Body = Body.None) = call(Request(route, vars, body = body))

    /** Every item of every page of [route], followed by `next_cursor` (B1); [base] is the query each page starts from. */
    fun pages(ctx: Ctx, route: Route, vars: List<Pair<String, String>> = emptyList(), base: List<Pair<String, String>> = emptyList()): List<JsonNode> {
        val items = mutableListOf<JsonNode>()
        var cursor: String? = null
        do {
            val query = base + listOfNotNull(cursor?.let { "cursor" to it })
            val page = ctx.call(Request(route, vars, query))
            items.addAll(page.items("items"))
            cursor = page.strOrNull("next_cursor")
        } while (cursor != null)
        return items
    }

    fun list(ctx: Ctx, k: Kind): List<JsonNode> = pages(ctx, Routes.objectRoute(k.word, Op.LIST), base = listOf("kind" to k.kind))

    fun get(ctx: Ctx, k: Kind, id: String): JsonNode = ctx.send(Routes.objectRoute(k.word, Op.GET), listOf("id" to id))

    fun rename(ctx: Ctx, k: Kind, id: String, name: String): JsonNode =
        ctx.send(Routes.objectRoute(k.word, Op.RENAME), listOf("id" to id), Body.JsonBody(Json.obj("name" to name)))

    /** The object and its first draft: `{kind, name, parent_id?, body}`. */
    fun create(ctx: Ctx, k: Kind, name: String, parent: String?, body: JsonNode): JsonNode {
        val request = Json.obj("kind" to k.kind, "name" to name, "body" to body)
        if (parent != null) request.put("parent_id", parent)
        return ctx.send(Routes.objectRoute(k.word, Op.CREATE), emptyList(), Body.JsonBody(request))
    }

    fun versionOp(ctx: Ctx, k: Kind, op: Op, id: String, semver: String): JsonNode =
        ctx.send(Routes.objectRoute(k.word, op), listOf("id" to id, "semver" to semver))

    /** A private object skips review, so a version is published at once (16.10.26v §4). A public one stays a draft for `apps submit`. */
    fun publishIfPrivate(ctx: Ctx, k: Kind, obj: JsonNode, semver: String): JsonNode? {
        if (obj.str("visibility") == "public" || semver.isEmpty()) return null
        return versionOp(ctx, k, Op.PUBLISH, obj.str("id"), semver)
    }

    /**
     * D2's body update: overlay the flags on the one editable version
     * (`version_put` at its `draft_version`), else on the serving (or
     * highest) version as a new one (`version_create`). Always a whole body.
     */
    fun bodyUpdate(ctx: Ctx, k: Kind, id: String, overlay: (ObjectNode) -> Unit): JsonNode {
        val current = get(ctx, k, id)
        val versions = current.items("versions")
        val editable = versions.firstOrNull { it.str("state") in setOf("draft", "changes_requested") }?.strOrNull("semver")
        val answer = if (editable != null) {
            val version = versionOp(ctx, k, Op.VERSION_GET, id, editable)
            val edit = Json.obj("body" to overlaid(version.path("body"), overlay), "draft_version" to version.path("draft_version"))
            ctx.send(Routes.objectRoute(k.word, Op.VERSION_PUT), listOf("id" to id, "semver" to editable), Body.JsonBody(edit))
        } else {
            val base = current.strOrNull("serving_version") ?: versions.firstOrNull()?.strOrNull("semver")
            val start = base?.let { versionOp(ctx, k, Op.VERSION_GET, id, it).path("body") } ?: Json.obj()
            ctx.send(Routes.objectRoute(k.word, Op.VERSION_CREATE), listOf("id" to id), Body.JsonBody(Json.obj("body" to overlaid(start, overlay))))
        }
        return publishIfPrivate(ctx, k, current, answer.str("semver")) ?: answer
    }

    private fun overlaid(body: JsonNode, overlay: (ObjectNode) -> Unit): ObjectNode =
        (if (body.isObject) body.deepCopy<ObjectNode>() else Json.obj()).also(overlay)

    /** The destructive request: the canonical email in its body (B3.6). */
    fun delete(ctx: Ctx, k: Kind, id: String, version: String?, email: String): JsonNode {
        val confirm = Body.JsonBody(Json.obj("confirm" to email))
        return if (version != null) {
            ctx.send(Routes.objectRoute(k.word, Op.DELETE_VERSION), listOf("id" to id, "semver" to version), confirm)
        } else {
            ctx.send(Routes.objectRoute(k.word, Op.DELETE), listOf("id" to id), confirm)
        }
    }

    /** `{secret_id, prefix, secret, expires_at}`, revealed once. */
    fun secretCreate(ctx: Ctx, k: Kind, id: String): JsonNode = ctx.send(Routes.objectRoute(k.word, Op.SECRET_CREATE), listOf("id" to id))

    /** A `get` printed: the server's JSON, or B1's `FIELD  VALUE` table. */
    fun printGet(ctx: Ctx, k: Kind, id: String) = ctx.out.value(get(ctx, k, id))

    /** Every page of [k], printed: one JSON array, or the schema's columns. */
    fun printList(ctx: Ctx, k: Kind, columns: Columns) = printRows(ctx, list(ctx, k), columns)

    fun printRows(ctx: Ctx, items: List<JsonNode>, columns: Columns) =
        ctx.out.list(items, columns.map { it.first }) { item -> columns.map { (_, key) -> Table.cell(item.path(key)) } }
}
