// `lingara apps update [name]` without `--manifest` (ADR 16.10.26ae D6): the
// project's manifest goes to the **dev version** (`PATCH …/dev`, never its
// `render_url`), or with `--version` becomes an immutable draft. A row with
// no id is registered first.
package com.getlingara.cli.kotlin.apps

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.auth.call
import com.getlingara.cli.kotlin.core.Exit
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Json
import com.getlingara.cli.kotlin.core.str
import com.getlingara.cli.kotlin.http.Body
import com.getlingara.cli.kotlin.http.Op
import com.getlingara.cli.kotlin.http.Request
import com.getlingara.cli.kotlin.http.Routes

object ProjectUpdate {
    fun run(ctx: Ctx, args: UpdateArgs) {
        val (toml, cwd) = Rows.open(ctx, args.config)
        val row = Rows.resolve(toml, args.id, cwd)
        val manifest = Project.read(toml, row, cwd)
        if (args.version != null && ManifestRules.ephemeral(manifest.str("render_url"))) {
            throw Fail.message(Exit.RENDER_URL_EPHEMERAL, "render_url_ephemeral", "file" to Project.source(toml, row, cwd))
        }
        val warnings = Project.warn(ctx, manifest)
        val id = row.id ?: run {
            Project.register(ctx, toml, row, manifest)
            return
        }
        val answer = if (args.version != null) {
            cut(ctx, id, args.version, manifest)
        } else {
            DevApi.patchWithRetry(ctx, id) { draftVersion -> Json.obj("manifest" to manifest, "draft_version" to draftVersion) }
        }
        print(ctx, answer, warnings)
    }

    /** Step 3: an immutable draft from the project's manifest. The server's lifecycle rules stand, as `refused`. */
    private fun cut(ctx: Ctx, id: String, semver: String, manifest: JsonNode): JsonNode {
        val body = Json.obj("semver" to semver, "body" to Json.obj("manifest" to manifest))
        return ctx.call(Request(Routes.objectRoute("app", Op.VERSION_CREATE), listOf("id" to id), body = Body.JsonBody(body)))
    }

    /** The server's answer, with `warnings: [code]` under `--json`. */
    private fun print(ctx: Ctx, answer: JsonNode, warnings: List<String>) {
        if (ctx.out.json && answer is ObjectNode) answer.set<JsonNode>("warnings", Json.of(warnings))
        ctx.out.value(answer)
    }
}
