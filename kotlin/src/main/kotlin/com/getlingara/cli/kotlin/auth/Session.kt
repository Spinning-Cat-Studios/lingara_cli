// The signed-in session and every authenticated call (ADR 16.10.26ad D3,
// contract B2 rule 2): a `401` to a bearer request refreshes once (a fresh
// `client_credentials` mint under CI) and replays once; a second `401` is
// `auth_required`, and any other answer to the replay is classified again.
package com.getlingara.cli.kotlin.auth

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.core.Exit
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Json
import com.getlingara.cli.kotlin.core.Stamp
import com.getlingara.cli.kotlin.core.str
import com.getlingara.cli.kotlin.http.Answer
import com.getlingara.cli.kotlin.http.Auth
import com.getlingara.cli.kotlin.http.Body
import com.getlingara.cli.kotlin.http.Classify
import com.getlingara.cli.kotlin.http.Fixed
import com.getlingara.cli.kotlin.http.Request
import com.getlingara.cli.kotlin.http.Route
import com.getlingara.cli.kotlin.http.Verdict
import com.getlingara.cli.kotlin.http.WireResponse
import com.getlingara.cli.kotlin.store.ProfileRecord

/** The four scopes, in one grant: the platform pair opens `/platform/…`, the canvas pair `/v1/canvases` (16.10.26u §3). */
const val SCOPES = "platform:read platform:write canvas:read canvas:write"

/** A stored access token this close to expiry is refreshed first (D3). */
private const val REFRESH_SKEW_SECS = 60L

/** Where the session's credentials came from. */
sealed interface Cred {
    /** `LINGARA_PLATFORM_CLIENT_ID` / `_SECRET`: memory only, nothing stored. */
    data class Ci(val id: String, val secret: String) : Cred

    data class Stored(val record: ProfileRecord) : Cred
}

class SessionState(
    val cred: Cred,
    var access: String,
    /** The email `--confirm` is checked against: the record's, or under CI `developer.me`'s, read once (D5 step 2). */
    var email: String?,
    val scopes: String,
)

/** A 2xx body as JSON; an empty one is `{}`. */
private fun bodyJson(reply: WireResponse, route: Route): JsonNode {
    if (reply.body.all { it.toInt().toChar().isWhitespace() }) return Json.obj()
    return Json.parse(reply.body) ?: throw Fail.failure("unexpected response from ${route.name}")
}

private fun answer(route: Route, reply: WireResponse, bearer: Boolean) =
    Answer(route, bearer, reply.status, reply.body, reply.header("retry-after")?.trim()?.toLongOrNull())

/** The refusal for a non-2xx reply that is not rule 2's. */
fun refusal(route: Route, reply: WireResponse, bearer: Boolean): Fail {
    val a = answer(route, reply, bearer)
    val exit = (Classify.classify(a) as? Verdict.Ends)?.exit ?: Exit.AUTH_REQUIRED
    val fail = Hints.advise(Classify.failFor(a, exit))
    if (a.member("lingara_code") == "developer_account_required" && Hints.PORTAL_LINE !in fail.hints) fail.hint(Hints.PORTAL_LINE)
    return fail
}

private fun ok(status: Int) = status in 200..299

/** The CI pair, when both halves are set (D3 rule 1). */
fun Ctx.ciPair(): Pair<String, String>? {
    val id = app.env.get("LINGARA_PLATFORM_CLIENT_ID") ?: return null
    val secret = app.env.get("LINGARA_PLATFORM_CLIENT_SECRET") ?: return null
    return id to secret
}

/** A token-endpoint form `POST`: no bearer, classified as a token route. */
fun Ctx.tokenPost(fixed: Fixed, form: List<Pair<String, String>>, auth: Auth = Auth.None): JsonNode {
    val reply = app.http.send(Request(fixed.route, body = Body.Form(form)), auth)
    return if (ok(reply.status)) bodyJson(reply, fixed.route) else throw refusal(fixed.route, reply, false)
}

private fun Ctx.mint(id: String, secret: String): Tokens {
    val form = listOf("grant_type" to "client_credentials", "scope" to SCOPES)
    return Tokens.read(tokenPost(Fixed.TOKEN, form, Auth.Basic(id, secret)))
}

private fun Ctx.open(): SessionState {
    ciPair()?.let { (id, secret) ->
        val tokens = mint(id, secret)
        return SessionState(Cred.Ci(id, secret), tokens.access, null, tokens.scope ?: SCOPES)
    }
    val record = app.stores.load(profile) ?: throw Fail.auth("not signed in: run `lingara login`")
    val expires = Stamp.parse(record.accessExpiresAt)
    val stale = expires == null || expires - app.http.clock.now() < REFRESH_SKEW_SECS
    app.session = SessionState(Cred.Stored(record), record.accessToken, record.email, record.scopes)
    if (stale) refresh()
    return app.session ?: throw Fail.failure("the session was lost")
}

/** The bearer token, opening the session on first use. */
fun Ctx.bearer(): String {
    val session = app.session ?: open().also { app.session = it }
    return session.access
}

/** Rule 2's one refresh: the stored refresh token, or a fresh CI mint. A refused refresh never deletes the stored credential (D3). */
private fun Ctx.refresh() {
    val session = app.session ?: throw Fail.auth("not signed in: run `lingara login`")
    when (val cred = session.cred) {
        is Cred.Ci -> session.access = mint(cred.id, cred.secret).access
        is Cred.Stored -> {
            val form = listOf("grant_type" to "refresh_token", "refresh_token" to cred.record.refreshToken, "client_id" to app.endpoints.clientId)
            val tokens = try {
                tokenPost(Fixed.TOKEN, form)
            } catch (fail: Fail) {
                // A refused refresh says "run `lingara login`", unless it is D8's dark signal, which has its own sentence.
                throw if (fail.exit == Exit.AUTH_REQUIRED) fail.hint("run `lingara login`") else fail
            }
            val record = Tokens.read(tokens).applyTo(cred.record, app.http.clock.now())
            saveRecord(record)
            app.session = SessionState(Cred.Stored(record), record.accessToken, record.email, record.scopes)
        }
    }
}

/** Writes the profile record, printing the store's one fallback warning. */
fun Ctx.saveRecord(record: ProfileRecord) {
    app.stores.save(profile, record)?.let(out::note)
}

/** One authenticated call: send, refresh and replay once on a `401`, classify anything else. */
fun Ctx.call(request: Request): JsonNode {
    val reply = app.http.send(request, Auth.Bearer(bearer()))
    if (ok(reply.status)) return bodyJson(reply, request.route)
    if (Classify.classify(answer(request.route, reply, true)) != Verdict.Refresh) throw refusal(request.route, reply, true)
    refresh()
    val replay = app.http.send(request, Auth.Bearer(bearer()))
    return when {
        ok(replay.status) -> bodyJson(replay, request.route)
        replay.status == 401 -> throw Fail.auth("the platform refused the credentials after a refresh: run `lingara login`")
        else -> throw refusal(request.route, replay, true)
    }
}

/** The email `--confirm` and the typed line are compared against. */
fun Ctx.email(): String {
    bearer()
    app.session?.email?.let { return it }
    val email = call(Request(Fixed.DEVELOPER_ME.route)).str("email")
    app.session?.email = email
    return email
}
