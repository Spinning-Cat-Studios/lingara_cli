// Requests: one function spells a route onto a base, adds contract B5's
// headers, retries what 16.10.26ad D6 lets it retry, and hands back the raw
// answer. Classifying it is `Classify`'s; credentials are `auth.Session`'s.
package com.getlingara.cli.kotlin.http

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.core.Exit
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Json
import java.io.IOException
import java.util.Base64

sealed interface Body {
    object None : Body

    data class JsonBody(val value: JsonNode) : Body

    data class Form(val pairs: List<Pair<String, String>>) : Body
}

sealed interface Auth {
    object None : Auth

    data class Bearer(val token: String) : Auth

    /** RFC 6749 `client_secret_basic`, the CI mint (16.10.26ad D3). */
    data class Basic(val id: String, val secret: String) : Auth
}

data class Request(
    val route: Route,
    val vars: List<Pair<String, String>> = emptyList(),
    val query: List<Pair<String, String>> = emptyList(),
    val body: Body = Body.None,
)

/** The time a run reads and waits on, so tests never sleep. */
interface Clock {
    fun now(): Long

    fun sleep(secs: Long)
}

object SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis() / 1000

    override fun sleep(secs: Long) = Thread.sleep(secs * 1000)
}

class Bases(var api: String, var portal: String)

/** What every request carries besides its route (B5): who is asking, in which language. */
data class Identity(val userAgent: String, val acceptLanguage: String?)

class Http(val transport: Transport, val clock: Clock, val bases: Bases, val identity: Identity) {
    /** The route's absolute URL with [vars] filled and [query] appended. */
    fun url(route: Route, vars: List<Pair<String, String>>, query: List<Pair<String, String>>): String {
        val base = if (route.base == Base.PORTAL) bases.portal else bases.api
        val path = vars.fold(route.path) { p, (k, v) -> p.replace("{$k}", encode(v)) }
        val joined = if (query.isEmpty()) "" else "?" + query.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        return base.trimEnd('/') + path + joined
    }

    private fun wire(request: Request, auth: Auth): WireRequest {
        val headers = mutableListOf("user-agent" to identity.userAgent, "accept" to "application/json")
        identity.acceptLanguage?.let { headers.add("accept-language" to it) }
        when (auth) {
            is Auth.Bearer -> headers.add("authorization" to "Bearer ${auth.token}")
            is Auth.Basic -> headers.add("authorization" to basic(auth.id, auth.secret))
            Auth.None -> {}
        }
        val body = when (val b = request.body) {
            Body.None -> ByteArray(0)
            is Body.JsonBody -> Json.write(b.value).toByteArray().also { headers.add("content-type" to "application/json") }
            is Body.Form -> form(b.pairs).toByteArray().also { headers.add("content-type" to "application/x-www-form-urlencoded") }
        }
        return WireRequest(request.route.method, url(request.route, request.vars, request.query), headers, body)
    }

    /**
     * One request, with D6's retries: a `GET`, `PUT`, `PATCH` or `DELETE`
     * answered 429 or 503 with `Retry-After` is retried up to twice within
     * 30 s. A `POST` is never retried after a response (B5).
     */
    fun send(request: Request, auth: Auth): WireResponse {
        val wire = wire(request, auth)
        var waited = 0L
        for (attempt in 0..RETRIES) {
            val response = try {
                transport.send(wire)
            } catch (e: IOException) {
                throw Fail(Exit.UNAVAILABLE, "unavailable", "${request.route.name} could not be reached: ${e.message ?: e.javaClass.simpleName}")
            }
            if (attempt > 0 && replayedDeleteDone(wire, response)) return WireResponse(204, emptyList(), ByteArray(0))
            val secs = retryWait(wire, response)
            if (secs == null || attempt >= RETRIES || waited + secs > RETRY_BUDGET_SECS) return response
            clock.sleep(secs)
            waited += secs
        }
        throw Fail.failure("unreachable retry loop")
    }

    companion object {
        private const val RETRY_BUDGET_SECS = 30L
        private const val RETRIES = 2

        /** The wait an idempotent request may retry after. */
        private fun retryWait(wire: WireRequest, response: WireResponse): Long? {
            val idempotent = wire.method in setOf("GET", "PUT", "PATCH", "DELETE")
            if (!idempotent || response.status !in setOf(429, 503)) return null
            return response.header("retry-after")?.trim()?.toLongOrNull()
        }

        /** A `DELETE` replayed after it succeeded answers a coded 404 (D6). */
        private fun replayedDeleteDone(wire: WireRequest, response: WireResponse): Boolean {
            val coded = Json.parse(response.body)?.has("code") == true
            return wire.method == "DELETE" && response.status == 404 && coded
        }

        /** RFC 3986 unreserved characters pass; everything else is `%XX`. */
        fun encode(s: String): String = buildString {
            for (b in s.toByteArray(Charsets.UTF_8)) {
                val c = (b.toInt() and 0xff).toChar()
                if (c.isLetterOrDigit() && c.code < 128 || c in "-._~") append(c) else append("%%%02X".format(b.toInt() and 0xff))
            }
        }

        fun form(pairs: List<Pair<String, String>>): String = pairs.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }

        private fun basic(id: String, secret: String): String {
            val raw = "${encode(id)}:${encode(secret)}"
            return "Basic " + Base64.getEncoder().encodeToString(raw.toByteArray())
        }
    }
}
