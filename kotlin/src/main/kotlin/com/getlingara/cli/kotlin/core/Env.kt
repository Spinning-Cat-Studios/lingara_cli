// The process environment, read once, so tests pass their own; and the RFC
// 3339 UTC stamps the profile record's `access_expires_at` takes (ADR
// 16.10.26ad D4).
package com.getlingara.cli.kotlin.core

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class Env(val vars: Map<String, String>) {
    /** A set, non-empty variable. */
    fun get(name: String): String? = vars[name]?.takeIf { it.isNotEmpty() }

    companion object {
        fun fromProcess(): Env = Env(System.getenv())
    }
}

object Stamp {
    private val format: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    /** Seconds since the epoch, as `YYYY-MM-DDTHH:MM:SSZ`. */
    fun format(secs: Long): String = format.format(Instant.ofEpochSecond(secs))

    /** `YYYY-MM-DDTHH:MM:SS` then `Z` or nothing; fractions are ignored. */
    fun parse(text: String): Long? {
        if (text.length < 19 || text[10] !in "Tt") return null
        val head = text.substring(0, 10) + "T" + text.substring(11, 19)
        return runCatching { LocalDateTime.parse(head).toEpochSecond(ZoneOffset.UTC) }.getOrNull()
    }
}
