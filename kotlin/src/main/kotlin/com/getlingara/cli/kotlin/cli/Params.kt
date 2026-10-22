// The kit-local arg/type table (ADR 16.10.26af D2): Clikt's conversions
// (`.int()`, `.choice()`, a path) are not introspectable from its model, and
// neither is `.multiple()`, so a flag's `type`, `values` and repeatability
// are written here once. Registration reads this table to pick the
// conversion, and `__tree` reads it to print the type, so the projection is
// never a second hand-written tree. Names, `required` and booleans come
// back from Clikt's own model.
//
// Every flag of one name has one type wherever it appears in the schema, so
// the table is keyed by the flag's name. The schema's `conflicts`, which no
// Clikt construct carries without reshaping the options, are the second
// table, keyed by command path and checked once the leaf has parsed.
package com.getlingara.cli.kotlin.cli

/** D2's types: `string` unless the table says otherwise, `bool` for a Clikt flag. */
sealed class ParamType(val word: String) {
    object Text : ParamType("string")

    object PathType : ParamType("path")

    object IntType : ParamType("int")

    class Choice(val values: List<String>) : ParamType("enum")
}

object Params {
    private val path = ParamType.PathType

    private val types: Map<String, ParamType> = mapOf(
        "config" to path,
        "manifest" to path,
        "dir" to path,
        "file" to path,
        "path" to path,
        "write-env" to path,
        "port" to ParamType.IntType,
        "lang" to ParamType.Choice(listOf("typescript", "rust", "go", "java", "kotlin", "ruby", "php")),
        "slot" to ParamType.Choice(listOf("home.side", "plans.empty_detail")),
        "tunnel" to ParamType.Choice(listOf("cloudflared", "none")),
        "type" to ParamType.Choice(listOf("confidential", "public")),
        "billing-mode" to ParamType.Choice(listOf("allowance", "metered")),
    )

    /** The flags a developer may give more than once; each value is kept. */
    private val repeatable = setOf("slot", "context", "scope", "redirect-uri", "origin", "event")

    fun type(flag: String): ParamType = types[flag] ?: ParamType.Text

    fun repeatable(flag: String): Boolean = flag in repeatable

    /** The schema's `conflicts`, by command path: pairs never given together. */
    val conflicts: Map<String, List<Pair<String, String>>> = mapOf(
        "apps create" to listOf("lang", "bare", "dir", "no-register", "description", "port", "slot", "context").map { "manifest" to it } +
            listOf("lang", "bare", "dir").map { "client" to it },
        "apps update" to listOf("manifest" to "version"),
        "apps dev" to listOf("port", "tunnel", "render-url", "take-over", "write-env", "show-env").map { "reset" to it },
        "oauth_client create" to listOf("ci" to "type", "ci" to "scope"),
        "webhook outbound update" to listOf("enable" to "disable"),
        "canvas apply" to listOf("dry-run" to "print-commands"),
    )
}
