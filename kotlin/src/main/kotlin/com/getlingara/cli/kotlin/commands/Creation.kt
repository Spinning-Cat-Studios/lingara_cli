// A create that may take several calls (ADR 16.10.26ad D1, D2). It ends with
// one `<word>.get`, printed with any secret it minted. Failing part-way, it
// still reveals that secret on stdout and names every object it made on
// stderr, then exits with the failing call's code. Nothing is rolled back.
package com.getlingara.cli.kotlin.commands

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Json
import com.getlingara.cli.kotlin.core.Made
import com.getlingara.cli.kotlin.core.str
import com.getlingara.cli.kotlin.output.Table

class Creation(private val ctx: Ctx) {
    val made = mutableListOf<Made>()

    /** `{id, value}`, the D6 member a create's JSON gains. */
    var secret: JsonNode? = null

    /** One object: created, named as made, then published when private. */
    fun obj(k: Kind, name: String, parent: String?, body: JsonNode): JsonNode {
        val created = Objects.create(ctx, k, name, parent, body)
        made.add(Made(k.word, created.str("id")))
        Objects.publishIfPrivate(ctx, k, created, created.path("version").str("semver"))
        return created
    }

    /** The one-time secret. `409 object_projection_pending` means the client is still publishing (16.10.26v §5): `refused`, with a retry hint. */
    fun mintSecret(k: Kind, id: String) {
        val minted = try {
            Objects.secretCreate(ctx, k, id)
        } catch (fail: Fail) {
            if (fail.code == "object_projection_pending") fail.hint("the client is still publishing; run `lingara client secret create $id` in a moment")
            throw fail
        }
        secret = Json.obj("id" to minted.path("secret_id"), "value" to minted.path("secret"))
    }

    /** The final read and its printing, or the failure with what was made. */
    fun conclude(steps: () -> Pair<Kind, String>): JsonNode {
        try {
            val (k, id) = steps()
            return Objects.get(ctx, k, id).also { print(it) }
        } catch (fail: Fail) {
            print(null)
            fail.made = made.toList()
            throw fail
        }
    }

    private fun print(obj: JsonNode?) {
        val secret = secret
        if (obj == null && secret == null) return
        if (ctx.out.json) {
            val value: JsonNode = obj?.deepCopy<JsonNode>() ?: Json.obj()
            if (secret != null && value.isObject) (value as ObjectNode).set<JsonNode>("secret", secret)
            ctx.out.jsonValue(value)
        } else {
            val rows = (obj?.let(Table::fields) ?: emptyList()) + listOfNotNull(secret?.let { listOf("SECRET", Table.cell(it.path("value"))) })
            ctx.out.print(Table.render(listOf("FIELD", "VALUE"), rows))
        }
        if (secret != null) ctx.out.note("The secret is shown once: store it now. It cannot be read again.")
    }
}
