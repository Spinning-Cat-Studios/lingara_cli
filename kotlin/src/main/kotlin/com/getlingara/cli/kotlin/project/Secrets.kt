// 16.10.26ae D4's `platform_toml_secret`: the first value or key, in file
// order, that looks like a secret. A value is one when it matches
// `lgr_[a-z]+_[A-Za-z0-9]{8,}` and is not a public client id (`lgr_cid_…`);
// a key is one when its `_`-separated words include `secret`, `token` or
// `password`, case-insensitively (`CLIENT_SECRET`, `api_token`; never
// `TOKENIZER`). The refusal names the key, never the value.
package com.getlingara.cli.kotlin.project

import com.fasterxml.jackson.databind.JsonNode

object Secrets {
    private val words = listOf("secret", "token", "password")
    private val token = Regex("lgr_([a-z]+)_([A-Za-z0-9]{8,})")

    /** Whether [s] holds a real-looking `lgr_` token other than a client id. */
    fun secretValue(s: String): Boolean {
        var at = s.indexOf("lgr_")
        while (at >= 0) {
            val m = token.matchAt(s, at)
            if (m != null && m.groupValues[1] != "cid") return true
            at = s.indexOf("lgr_", at + 4)
        }
        return false
    }

    fun secretKey(key: String): Boolean = key.split('_').any { w -> words.any { it.equals(w, ignoreCase = true) } }

    /** The dotted path of the first secret-shaped key or value, if any. */
    fun firstSecret(value: JsonNode): String? = walk(value, "")

    private fun walk(value: JsonNode, at: String): String? = when {
        value.isTextual -> at.takeIf { secretValue(value.textValue()) }
        value.isArray -> value.firstNotNullOfOrNull { walk(it, at) }
        value.isObject -> value.properties().firstNotNullOfOrNull { (k, v) ->
            val path = if (at.isEmpty()) k else "$at.$k"
            if (secretKey(k)) path else walk(v, path)
        }
        else -> null
    }
}
