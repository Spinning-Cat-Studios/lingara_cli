// `lingara oauth_client …` (ADR 16.10.26ad D1, D2). Rotation is two
// commands, because it is two calls: `secret create` while the old secret
// stays live, then `secret delete` once the new one is deployed.
package com.getlingara.cli.kotlin.commands

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.auth.SCOPES
import com.getlingara.cli.kotlin.auth.call
import com.getlingara.cli.kotlin.core.Assets
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Json
import com.getlingara.cli.kotlin.core.items
import com.getlingara.cli.kotlin.core.str
import com.getlingara.cli.kotlin.http.Body
import com.getlingara.cli.kotlin.http.Op
import com.getlingara.cli.kotlin.http.Request
import com.getlingara.cli.kotlin.http.Routes

/** `client create`'s flags, the schema's defaults applied by the handler (Clikt carries none, so a given flag is told from a default). */
data class ClientCreate(
    val name: String,
    val scope: List<String> = emptyList(),
    val type: String? = null,
    val billingMode: String? = null,
    val redirectUri: List<String> = emptyList(),
    val ci: Boolean = false,
)

data class ClientUpdate(val id: String, val name: String?, val scope: List<String>, val apiVersion: String?, val redirectUri: List<String>)

object OauthClient {
    val COLUMNS: Columns = listOf("ID" to "id", "NAME" to "name", "TYPE" to "client_type", "SCOPES" to "allowed_scopes", "CREATED" to "created_at")

    /** The client's version body (16.10.26v §1). `--ci` is confidential with the four scopes; whether the client may be one is the server's predicate. */
    fun body(args: ClientCreate): JsonNode {
        val type = if (args.ci) "confidential" else args.type ?: "confidential"
        val scopes = if (args.ci) SCOPES.split(' ') else args.scope
        return Json.obj("client_type" to type, "allowed_scopes" to scopes, "billing_mode" to (args.billingMode ?: "allowance"), "redirect_uris" to args.redirectUri)
    }

    /** Create, publish, mint the confidential client's one-time secret, read. */
    fun create(ctx: Ctx, args: ClientCreate): JsonNode {
        val made = Creation(ctx)
        val confidential = args.ci || (args.type ?: "confidential") == "confidential"
        return made.conclude {
            val id = made.obj(Kind.OAUTH_CLIENT, args.name, null, body(args)).str("id")
            if (confidential) made.mintSecret(Kind.OAUTH_CLIENT, id)
            Kind.OAUTH_CLIENT to id
        }
    }

    /** `--name` is a rename; any other flag is a body update. */
    fun update(ctx: Ctx, args: ClientUpdate) {
        var last: JsonNode? = args.name?.let { Objects.rename(ctx, Kind.OAUTH_CLIENT, args.id, it) }
        if (args.scope.isNotEmpty() || args.apiVersion != null || args.redirectUri.isNotEmpty()) {
            last = Objects.bodyUpdate(ctx, Kind.OAUTH_CLIENT, args.id) { body ->
                if (args.scope.isNotEmpty()) body.set<JsonNode>("allowed_scopes", Json.of(args.scope))
                args.apiVersion?.let { body.put("api_version", it) }
                if (args.redirectUri.isNotEmpty()) body.set<JsonNode>("redirect_uris", Json.of(args.redirectUri))
            }
        }
        ctx.out.value(last ?: throw Fail.usage("nothing to update: pass --name, --scope, --api-version or --redirect-uri"))
    }

    /** The children 16.10.26v §7's cascade deletes, counted by kind. */
    val consequence: Consequence = { obj ->
        val children = obj.items("children")
        val count = { kinds: Set<String> -> children.count { it.str("kind") in kinds }.toString() }
        Assets.message(
            "consequence_oauth_client",
            "apps" to count(setOf("app")), "embeds" to count(setOf("embed")), "webhooks" to count(setOf("webhook_in", "webhook_out")),
        )
    }

    fun secretCreate(ctx: Ctx, clientId: String) {
        ctx.out.value(Objects.secretCreate(ctx, Kind.OAUTH_CLIENT, clientId))
        ctx.out.note("The secret is shown once: store it now. It cannot be read again.")
    }

    /** A secret has no read of its own, so the prompt names the client's. */
    fun secretDelete(ctx: Ctx, clientId: String, secretId: String, confirm: ConfirmArgs) {
        val email = Confirm.destructive(ctx, confirm) { c ->
            val client = Objects.get(c, Kind.OAUTH_CLIENT, clientId)
            Deletes.prompt(Kind.OAUTH_CLIENT, client, secretId, null) { Assets.message("consequence_secret") }.copy(kind = "secret", pinnedBy = 0)
        }
        val route = Routes.objectRoute(Kind.OAUTH_CLIENT.word, Op.SECRET_DELETE)
        ctx.call(Request(route, listOf("id" to clientId, "secret_id" to secretId), body = Body.JsonBody(Json.obj("confirm" to email))))
        ctx.out.nothing()
    }
}
