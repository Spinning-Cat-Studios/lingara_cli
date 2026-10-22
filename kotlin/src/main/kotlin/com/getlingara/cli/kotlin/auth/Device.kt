// `login --device` (RFC 8628, 16.10.26u §7): for SSH sessions and machines
// without a browser. `authorization_pending` keeps polling, `slow_down` adds
// 5 s, `expired_token` and `access_denied` are `auth_required`, and any answer
// carrying B4's `lingara_code` is `platform_disabled` (16.10.26ad D8).
package com.getlingara.cli.kotlin.auth

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.core.Assets
import com.getlingara.cli.kotlin.core.Exit
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Json
import com.getlingara.cli.kotlin.core.str
import com.getlingara.cli.kotlin.core.strOrNull
import com.getlingara.cli.kotlin.http.Auth
import com.getlingara.cli.kotlin.http.Body
import com.getlingara.cli.kotlin.http.Fixed
import com.getlingara.cli.kotlin.http.Request

private const val GRANT = "urn:ietf:params:oauth:grant-type:device_code"

private class Started(val deviceCode: String, var interval: Long, val expiresIn: Long)

private fun JsonNode.long(key: String, default: Long) = path(key).takeIf { it.isIntegralNumber }?.asLong() ?: default

/** The token response once the developer has approved the code. */
fun Ctx.deviceLogin(): JsonNode {
    val clientId = app.endpoints.clientId
    val start = tokenPost(Fixed.DEVICE_AUTHORIZATION, listOf("client_id" to clientId, "scope" to SCOPES))
    out.note(Assets.message("login_device_code", "verification_uri" to start.str("verification_uri"), "user_code" to start.str("user_code")))
    start.strOrNull("verification_uri_complete")?.let(out::note)
    val started = Started(start.str("device_code"), start.long("interval", 5), start.long("expires_in", 900))
    return poll(started, clientId)
}

/** Whether [error] means "ask again"; `slow_down` also widens the interval by 5 s. */
private fun keepPolling(error: String?, s: Started, waited: Long): Boolean {
    if (waited >= s.expiresIn) return false
    if (error == "slow_down") s.interval += 5
    return error == "authorization_pending" || error == "slow_down"
}

/** The RFC 8628 refusals this kit words itself; anything else is the classifier's. */
private fun refused(error: String?): Fail? = when (error) {
    "authorization_pending", "slow_down", "expired_token" -> Fail.auth("the code expired before it was approved; run `lingara login --device` again")
    "access_denied" -> Fail(Exit.AUTH_REQUIRED, "access_denied", "sign-in was declined")
    else -> null
}

private fun Ctx.poll(s: Started, clientId: String): JsonNode {
    val route = Fixed.TOKEN.route
    var waited = 0L
    while (true) {
        app.http.clock.sleep(s.interval)
        waited += s.interval
        val form = listOf("grant_type" to GRANT, "device_code" to s.deviceCode, "client_id" to clientId)
        val reply = app.http.send(Request(route, body = Body.Form(form)), Auth.None)
        if (reply.status in 200..299) return Json.parse(reply.body) ?: throw Fail.failure("unexpected response from oauth.token")
        val body = Json.parse(reply.body)
        val error = if (body?.has("lingara_code") == true) null else body?.strOrNull("error")
        if (!keepPolling(error, s, waited)) throw refused(error) ?: refusal(route, reply, false)
    }
}
