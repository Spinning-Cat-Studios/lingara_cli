// Contract B2's refusal on stderr: `error: <text> (<code>)` and any advice
// lines, or under `--json` one JSON line. What a part-way create made is
// named either way (16.10.26ad D2), so nothing it made is left unnamed.
package com.getlingara.cli.kotlin.output

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Json

object Errors {
    /** The lines [fail] prints on stderr. */
    fun lines(fail: Fail, json: Boolean): List<String> {
        if (json) return listOf(Json.write(jsonLine(fail)))
        return listOf("error: ${fail.text} (${fail.code})") + fail.hints + fail.made.map { "made: ${it.kind} ${it.id}" }
    }

    private fun jsonLine(fail: Fail) = Json.obj().also { root ->
        val error = Json.obj("code" to fail.code, "message" to fail.text, "exit" to fail.exit.code)
        if (fail.hints.isNotEmpty()) error.set<JsonNode>("hints", Json.of(fail.hints))
        if (fail.made.isNotEmpty()) error.set<JsonNode>("made", Json.of(fail.made.map { Json.obj("kind" to it.kind, "id" to it.id) }))
        root.set<JsonNode>("error", error)
    }
}
