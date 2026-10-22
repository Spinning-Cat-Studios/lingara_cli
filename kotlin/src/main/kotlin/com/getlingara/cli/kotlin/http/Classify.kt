// The one response classifier (ADR 16.10.26ad D8; contract B2). Its rules
// are **data**: an ordered list of (predicate, verdict) entries, the first
// match winning and the last the catch-all. 16.10.26ae D10's rule 4a is one
// more entry, after rule 4.
package com.getlingara.cli.kotlin.http

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.core.Assets
import com.getlingara.cli.kotlin.core.Exit
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Json
import com.getlingara.cli.kotlin.core.strOrNull

/** `conformance/routes.toml`'s two codes, as this kit keys them. */
const val PLATFORM_DISABLED_CODE = "platform_disabled"
const val PLATFORM_ACCESS_CODE = "platform_access_not_enabled"

/** A non-2xx answer, with what the rules read about the request behind it. */
class Answer(val route: Route, val bearer: Boolean, val status: Int, val body: ByteArray, val retryAfter: Long?) {
    private val parsed: JsonNode? by lazy { Json.parse(body) }

    fun member(key: String): String? = parsed?.strOrNull(key)

    /** `{code, error}`, the platform's and `/v1`'s envelope. */
    val coded: Boolean get() = member("code") != null && member("error") != null

    /** RFC 6749 / 8628: an `error` member and no `code`. */
    val oauthEnvelope: Boolean get() = member("error") != null && member("code") == null

    val blank: Boolean get() = body.all { it.toInt().toChar().isWhitespace() }
}

/** Rule 2's refresh (re-mint under CI) then one replay, or an exit. */
sealed interface Verdict {
    object Refresh : Verdict

    data class Ends(val exit: Exit) : Verdict
}

private class Rule(val matches: (Answer) -> Boolean, val verdict: (Answer) -> Verdict)

object Classify {
    private fun platformDisabled(a: Answer): Boolean {
        val code = a.member("code")
        val dark = a.status == 404 && a.route.isPlatform && code == PLATFORM_DISABLED_CODE
        val door = a.status == 403 && code == PLATFORM_ACCESS_CODE
        val token = a.route.isToken && a.member("lingara_code") == PLATFORM_ACCESS_CODE
        return dark || door || token
    }

    /** Rule 4's one exception is keyed on route **and** code. */
    private fun codedNotFound(a: Answer): Verdict {
        val developerGone = a.route.name == "developer.me" && a.member("code") == "developer_not_found"
        return Verdict.Ends(if (developerGone) Exit.REFUSED else Exit.NOT_FOUND)
    }

    /**
     * Rule 4a (16.10.26ae D10): a dev-session `409` is a row of its own,
     * keyed on (route, body code), because a script branches on "someone else
     * is developing this app" where it would not on any other refusal.
     */
    private val devSessionCodes = listOf(
        Triple("app_dev.session_open", "dev_session_held", Exit.DEV_SESSION_HELD),
        Triple("app_dev.patch", "dev_session_held", Exit.DEV_SESSION_HELD),
        Triple("app_dev.session_renew", "dev_session_not_live", Exit.DEV_SESSION_LOST),
        Triple("app_dev.session_renew", "dev_session_expired", Exit.DEV_SESSION_LOST),
    )

    private fun devSessionRow(a: Answer): Exit? {
        val code = a.member("code") ?: return null
        val row = devSessionCodes.firstOrNull { it.first == a.route.name && it.second == code } ?: return null
        return row.third.takeIf { a.status == 409 }
    }

    private val rules = listOf(
        Rule(::platformDisabled) { Verdict.Ends(Exit.PLATFORM_DISABLED) },
        Rule({ it.status == 401 && it.bearer && !it.route.isToken }) { Verdict.Refresh },
        Rule({ it.route.isToken && it.status in 400..499 && it.status != 429 && it.oauthEnvelope }) { Verdict.Ends(Exit.AUTH_REQUIRED) },
        Rule({ it.status == 404 && it.coded }, ::codedNotFound),
        Rule({ devSessionRow(it) != null }) { Verdict.Ends(devSessionRow(it) ?: Exit.REFUSED) },
        Rule({ it.status == 404 && it.blank }) { Verdict.Ends(Exit.FAILURE) },
        Rule({ it.status in setOf(400, 403, 409, 422) }) { Verdict.Ends(Exit.REFUSED) },
        Rule({ it.status == 429 || it.status >= 500 }) { Verdict.Ends(Exit.UNAVAILABLE) },
        Rule({ true }) { Verdict.Ends(Exit.FAILURE) },
    )

    fun classify(answer: Answer): Verdict = rules.first { it.matches(answer) }.verdict(answer)

    /** RFC 6749's envelope: `error` is the code, `error_description` the text. */
    private fun oauthFail(answer: Answer) = Fail(
        Exit.AUTH_REQUIRED,
        answer.member("error") ?: "auth_required",
        answer.member("error_description") ?: "${answer.route.name} refused the credentials",
    )

    /** The platform's envelope: `code` and `error`, else the exit's own word and the status. */
    private fun codedFail(answer: Answer, exit: Exit) = Fail(
        exit,
        answer.member("code") ?: if (exit == Exit.UNAVAILABLE) "unavailable" else "refused",
        answer.member("error") ?: "${answer.route.name} answered HTTP ${answer.status}",
    )

    /** The refusal B2 prints for [exit]: the server's own code and text where it sent them, the kit's sentence where it did not. */
    fun failFor(answer: Answer, exit: Exit): Fail {
        val fail = when (exit) {
            Exit.PLATFORM_DISABLED -> Fail(exit, PLATFORM_DISABLED_CODE, Assets.message("platform_disabled"))
            Exit.FAILURE -> Fail(Exit.FAILURE, "unexpected_response", "unexpected response from ${answer.route.name}")
            Exit.AUTH_REQUIRED -> oauthFail(answer)
            else -> codedFail(answer, exit)
        }
        if (exit == Exit.UNAVAILABLE && answer.retryAfter != null) fail.text = "${fail.text} (retry after ${answer.retryAfter} s)"
        // A command that re-words a refusal (apps dev's `dev_session_held`) reads the server's other members from here.
        fail.body = String(answer.body, Charsets.UTF_8)
        return fail
    }
}
