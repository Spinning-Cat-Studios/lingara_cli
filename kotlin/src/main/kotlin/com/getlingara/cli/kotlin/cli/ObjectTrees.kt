// `lingara embed …`, `lingara oauth_client …` (alias `client`) and
// `lingara webhook inbound|outbound …` (ADR 16.10.26ad D1): the object kinds
// whose verbs are this kit's `commands` handlers over `/platform/objects`.
package com.getlingara.cli.kotlin.cli

import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.commands.ChildUpdate
import com.getlingara.cli.kotlin.commands.ClientCreate
import com.getlingara.cli.kotlin.commands.ClientUpdate
import com.getlingara.cli.kotlin.commands.Deletes
import com.getlingara.cli.kotlin.commands.Embed
import com.getlingara.cli.kotlin.commands.Kind
import com.getlingara.cli.kotlin.commands.OauthClient

class EmbedCreate : Leaf("create", "Create an embed and its metered OAuth client, and show the client's secret once.") {
    private val name by need("name", "The embed's name")
    private val origin by many("origin", "A web origin allowed to use it (up to 10)", "origin")
    private val scope by many("scope", "A scope beside embed:mint", "scope")

    override fun execute(ctx: Ctx) = Embed.create(ctx, name, origin, scope)
}

/** `embed update` and `webhook inbound update`: a rename, and one list flag that replaces a body member. */
class ChildUpdateLeaf(private val kind: Kind, summary: String, private val flag: String, flagHelp: String, private val member: String) :
    Leaf("update", summary) {
    private val id by arg("id")
    private val name by opt("name", "The new name")
    private val values by many(flag, flagHelp, if (flag == "origin") "origin" else "type")

    override fun execute(ctx: Ctx) = Embed.update(ctx, kind, ChildUpdate(id, name, values), member)
}

class ClientCreateLeaf : Leaf("create", "Create an OAuth client; a confidential one's secret is shown once.") {
    private val name by need("name", "The client's name")
    private val scope by many("scope", "A scope the client may ask for", "scope")
    private val type by opt("type", "Whether the client keeps a secret; default confidential")
    private val billingMode by opt("billing-mode", "How its usage is billed; default allowance", "mode")
    private val redirectUri by many("redirect-uri", "A redirect URI it may use", "uri")
    private val ci by switch("ci", "A CI client: confidential, holding the four platform and canvas scopes")

    override fun given() = setOfNotNull("ci".takeIf { ci }, "type".takeIf { type != null }, "scope".takeIf { scope.isNotEmpty() })

    override fun execute(ctx: Ctx) {
        OauthClient.create(ctx, ClientCreate(name, scope, type, billingMode, redirectUri, ci))
    }
}

class ClientUpdateLeaf : Leaf("update", "Rename an OAuth client, or replace its scopes, API version or redirect URIs.") {
    private val id by arg("id")
    private val name by opt("name", "The new name")
    private val scope by many("scope", "Replaces the scopes", "scope")
    private val apiVersion by opt("api-version", "The API version it is pinned to", "version")
    private val redirectUri by many("redirect-uri", "Replaces the redirect URIs", "uri")

    override fun execute(ctx: Ctx) = OauthClient.update(ctx, ClientUpdate(id, name, scope, apiVersion, redirectUri))
}

class SecretCreate : Leaf("create", "Mint a new secret, shown once. The old one stays live.") {
    private val clientId by arg("client_id")

    override fun execute(ctx: Ctx) = OauthClient.secretCreate(ctx, clientId)
}

class SecretDelete : Dangerous("delete", "Revoke a secret.") {
    private val clientId by arg("client_id")
    private val secretId by arg("secret_id")

    override fun execute(ctx: Ctx) = OauthClient.secretDelete(ctx, clientId, secretId, confirm)
}

object ObjectTrees {
    fun embed(): Topic = Topic(
        "embed",
        "Lingara inside your game or website: an embed and the metered OAuth client behind it.",
        EmbedCreate(),
        ListLeaf(Kind.EMBED, Embed.COLUMNS, "List your embeds."),
        GetLeaf(Kind.EMBED, "Show one embed."),
        ChildUpdateLeaf(Kind.EMBED, "Rename an embed, or replace its origins.", "origin", "Replaces the allowed origins", "origins"),
        DeleteLeaf(Kind.EMBED, "Delete an embed and its OAuth client, or one version.", Deletes.naming("consequence_embed")),
    )

    fun oauthClient(): Topic = Topic(
        "oauth_client",
        "OAuth clients: the credentials your servers, apps and CI use.",
        ClientCreateLeaf(),
        ListLeaf(Kind.OAUTH_CLIENT, OauthClient.COLUMNS, "List your OAuth clients."),
        GetLeaf(Kind.OAUTH_CLIENT, "Show one OAuth client."),
        ClientUpdateLeaf(),
        DeleteLeaf(Kind.OAUTH_CLIENT, "Delete an OAuth client and everything under it, or one version.", OauthClient.consequence),
        Topic("secret", "A client's secrets. Rotate by creating the new one, deploying it, then deleting the old one.", SecretCreate(), SecretDelete()),
    )

    fun webhook(): Topic = Topic(
        "webhook",
        "Webhooks: events your game sends in, and events Lingara sends out.",
        WebhookTrees.inbound(),
        WebhookTrees.outbound(),
    )
}
