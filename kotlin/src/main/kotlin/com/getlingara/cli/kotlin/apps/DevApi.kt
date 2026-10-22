// The five `app_dev.*` routes (Backend 16.10.26v §12; ADR 16.10.26ae R1–R3),
// one function each, plus the read-then-patch every writer of the dev
// version uses: a `409 version_conflict` re-reads once and retries, and a
// second one is the server's `refused`.
package com.getlingara.cli.kotlin.apps

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.auth.call
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Json
import com.getlingara.cli.kotlin.core.str
import com.getlingara.cli.kotlin.core.strOrNull
import com.getlingara.cli.kotlin.http.Body
import com.getlingara.cli.kotlin.http.Fixed
import com.getlingara.cli.kotlin.http.Request

/** What an open answers: the session, and the secrets it minted, which live only in this process's memory and the app's environment (D5). */
data class DevSession(val id: String, val leaseSecs: Long, val expiresAt: String, val appSecret: String, val clientSecret: String?)

object DevApi {
    private fun call(ctx: Ctx, route: Fixed, vars: List<Pair<String, String>>, body: Body = Body.None): JsonNode =
        ctx.call(Request(route.route, vars, body = body))

    /** `GET …/dev`: the manifest, `render_url`, `draft_version`, the session. */
    fun get(ctx: Ctx, id: String): JsonNode = call(ctx, Fixed.APP_DEV_GET, listOf("id" to id))

    private fun patch(ctx: Ctx, id: String, body: JsonNode): JsonNode = call(ctx, Fixed.APP_DEV_PATCH, listOf("id" to id), Body.JsonBody(body))

    /** Reads `draft_version`, then patches `build(draft_version)`; one re-read and retry on `version_conflict`. */
    fun patchWithRetry(ctx: Ctx, id: String, build: (JsonNode) -> JsonNode): JsonNode {
        val draft = get(ctx, id).path("draft_version")
        return try {
            patch(ctx, id, build(draft))
        } catch (fail: Fail) {
            if (fail.code != "version_conflict") throw fail
            patch(ctx, id, build(get(ctx, id).path("draft_version")))
        }
    }

    /** `POST …/dev/session {take_over, credentials}`. */
    fun open(ctx: Ctx, id: String, takeOver: Boolean, client: Boolean): DevSession {
        val body = Json.obj("take_over" to takeOver)
        if (client) body.set<JsonNode>("credentials", Json.of(listOf("client")))
        val answer = call(ctx, Fixed.APP_DEV_SESSION_OPEN, listOf("id" to id), Body.JsonBody(body))
        val lease = answer.path("lease_secs").takeIf { it.isIntegralNumber && it.asLong() > 0 }?.asLong() ?: 300L
        return DevSession(
            id = answer.str("session_id"),
            leaseSecs = lease,
            expiresAt = answer.str("expires_at"),
            appSecret = answer.path("app_secret").str("secret"),
            clientSecret = answer.path("client_secret").strOrNull("secret"),
        )
    }

    fun renew(ctx: Ctx, id: String, sessionId: String): JsonNode = call(ctx, Fixed.APP_DEV_SESSION_RENEW, listOf("id" to id, "session_id" to sessionId))

    /** `DELETE …/dev/session/{sid}`: parks, keeps `render_url`, revokes the session's credentials. */
    fun end(ctx: Ctx, id: String, sessionId: String): JsonNode = call(ctx, Fixed.APP_DEV_SESSION_END, listOf("id" to id, "session_id" to sessionId))
}
