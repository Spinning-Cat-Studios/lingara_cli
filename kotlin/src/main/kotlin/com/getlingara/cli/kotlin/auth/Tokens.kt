// A token response and the profile record it becomes (ADR 16.10.26ad D4),
// and the one line of advice a refusal's code earns (D3, D5, D6), keyed by
// code, so a reworded server sentence changes nothing.
package com.getlingara.cli.kotlin.auth

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Stamp
import com.getlingara.cli.kotlin.core.strOrNull
import com.getlingara.cli.kotlin.store.ProfileRecord

/** RFC 6749 §5.1, read leniently: a missing `expires_in` is an hour. */
class Tokens(val access: String, val refresh: String?, val scope: String?, val expiresIn: Long) {
    /** [record] with these tokens: the refresh token rotates when one was sent, and `scopes` is rewritten whenever the answer carried `scope`. */
    fun applyTo(record: ProfileRecord, now: Long): ProfileRecord = record.copy(
        accessToken = access,
        refreshToken = refresh ?: record.refreshToken,
        scopes = scope ?: record.scopes,
        accessExpiresAt = Stamp.format(now + expiresIn),
    )

    companion object {
        fun read(value: JsonNode): Tokens {
            val expires = value.path("expires_in").takeIf { it.canConvertToLong() && it.isIntegralNumber }?.asLong() ?: 3600
            return Tokens(value.strOrNull("access_token") ?: "", value.strOrNull("refresh_token"), value.strOrNull("scope"), expires)
        }
    }
}

object Hints {
    const val PORTAL_LINE = "create your developer account at https://platform.getlingara.com"

    private val byCode = mapOf(
        "developer_not_found" to PORTAL_LINE,
        "developer_account_required" to PORTAL_LINE,
        "developer_terms_outdated" to "accept the updated developer terms at https://platform.getlingara.com",
        "delete_confirmation_mismatch" to "the platform holds a different email for you; run `lingara whoami`",
        "invalid_scope" to "recreate this client with `lingara client create --ci`",
    )

    /** [fail] with its code's line, once. */
    fun advise(fail: Fail): Fail {
        val line = byCode[fail.code]
        if (line != null && line !in fail.hints) fail.hint(line)
        return fail
    }
}
