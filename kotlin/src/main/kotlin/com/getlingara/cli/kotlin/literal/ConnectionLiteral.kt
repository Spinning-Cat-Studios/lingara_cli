// The connection literal subset (ADR 16.10.26ae D2; spec/connection-
// literal.md): `lingara/connection.js` read without evaluating JavaScript.
// `Lexer` makes the tokens, `Parser` the statements, `ManifestMap` turns the
// one `lingara.app` statement's `manifest` into the wire manifest.
package com.getlingara.cli.kotlin.literal

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.core.Exit
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Json

/** A 1-based line and column, columns counted in characters (code points). */
data class Pos(val line: Int, val column: Int)

/** Why the file is refused: a code at a position, or a scripted statement. */
sealed class Refusal : Exception() {
    data class At(val code: String, val at: Pos) : Refusal()

    data class Scripted(val handle: String) : Refusal()

    /** As B2 prints it: `connection_scripted`, else `usage` with `<file>:<line>:<column>: <what>`. */
    fun fail(file: String): Fail = when (this) {
        is Scripted -> Fail.message(Exit.CONNECTION_SCRIPTED, "connection_scripted", "file" to file, "statement" to handle)
        is At -> Fail(Exit.USAGE, "connection_literal", "$file:${at.line}:${at.column}: ${WHAT[code] ?: "not part of the literal subset"} ($code)")
    }
}

private val WHAT = mapOf(
    "header" to "the first line must be `// lingara connection v1 (schema 2)`",
    "comment" to "comments go inside a script; the builder rewrites everything outside them",
    "text" to "write text in quotes, with only the escapes JSON knows, and no raw tab or line break",
    "number" to "write finite numbers in decimal, as JSON does",
    "key" to "write each key once",
    "handle" to "this handle is already declared",
    "reference" to "this names no statement declared above it",
    "syntax" to "not part of the literal subset",
    "app_count" to "the file needs exactly one lingara.app statement",
    "option" to "an app takes only client and manifest",
    "manifest_key" to "not a manifest key lingara reads, or a required one is missing",
)

/** A literal value; a reference names an earlier statement's handle. */
sealed interface Lit {
    data class Str(val s: String) : Lit

    data class Num(val n: JsonNode) : Lit

    data class Bool(val b: Boolean) : Lit

    object Null : Lit

    data class Arr(val items: List<Lit>) : Lit

    /** The members, and where the object's `{` is. */
    data class Obj(val members: List<Member>, val at: Pos) : Lit

    data class Ref(val handle: String) : Lit

    /** As JSON; a reference becomes its handle. */
    fun toJson(): JsonNode = when (this) {
        is Str -> Json.of(s)
        is Ref -> Json.of(handle)
        is Num -> n
        is Bool -> Json.of(b)
        Null -> Json.of(null)
        is Arr -> Json.arr().also { a -> items.forEach { a.add(it.toJson()) } }
        is Obj -> Json.obj().also { o -> members.forEach { o.set<JsonNode>(it.key, it.value.toJson()) } }
    }
}

data class Member(val key: String, val at: Pos, val value: Lit)

/** `const <handle> = lingara.<kind>(<value>)`; [at] is where `const` is. */
data class Statement(val handle: String, val kind: String, val value: Lit, val at: Pos)

object ConnectionLiteral {
    /** The file's first line, byte for byte. */
    const val HEADER = "// lingara connection v1 (schema 2)"

    /** Every statement in [text]. */
    fun statements(text: String): List<Statement> {
        val header = text.substringBefore('\n')
        if (header.removeSuffix("\r") != HEADER) throw Refusal.At("header", Pos(1, 1))
        val body = if ('\n' in text) text.substringAfter('\n') else ""
        val lines = text.split("\n").let { if (text.endsWith("\n")) it.size - 1 else it.size }.coerceAtLeast(1)
        return Parser(Lexer(body, 2).tokens(), Pos(lines + 1, 1)).statements()
    }

    /** The wire manifest of the file's one `lingara.app` statement. */
    fun appManifest(text: String): JsonNode {
        val apps = statements(text).filter { it.kind == "app" }
        val app = when (apps.size) {
            1 -> apps[0]
            0 -> throw Refusal.At("app_count", Pos(1, 1))
            else -> throw Refusal.At("app_count", apps[1].at)
        }
        val value = app.value as? Lit.Obj ?: throw Refusal.At("syntax", app.at)
        value.members.firstOrNull { it.key != "client" && it.key != "manifest" }?.let { throw Refusal.At("option", it.at) }
        val manifest = value.members.firstOrNull { it.key == "manifest" }?.value as? Lit.Obj ?: throw Refusal.At("manifest_key", value.at)
        return ManifestMap.convert(manifest.members, manifest.at)
    }
}
