// The literal subset's tokens (spec/connection-literal.md): identifiers,
// JSON-escaped strings in either quote, finite JSON numbers and punctuation,
// each with its 1-based line and column. A comment, a template literal or a
// character the subset does not know stops the scan.
package com.getlingara.cli.kotlin.literal

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.core.Json

sealed interface Tok {
    data class Ident(val s: String) : Tok

    data class Str(val s: String) : Tok

    data class Num(val n: JsonNode) : Tok

    data class Punct(val c: Int) : Tok
}

data class Token(val tok: Tok, val at: Pos)

private val SIMPLE_ESCAPES = mapOf('"' to '"', '\\' to '\\', '/' to '/', 'b' to '\b', 'f' to '\u000C', 'n' to '\n', 'r' to '\r', 't' to '\t')
    .entries.associate { (k, v) -> k.code to v.code }

private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

/** Code points, so a column counts what the eye counts, as the reference kits do. */
class Lexer(text: String, private var line: Int) {
    private val chars: IntArray = text.codePoints().toArray()
    private var i = 0
    private var col = 1

    private fun pos() = Pos(line, col)

    private fun peek(ahead: Int = 0): Int? = chars.getOrNull(i + ahead)

    private fun bump(): Int? {
        val c = chars.getOrNull(i) ?: return null
        i++
        if (c == '\n'.code) {
            line++
            col = 1
        } else {
            col++
        }
        return c
    }

    private fun takeWhile(keep: (Int) -> Boolean): String = buildString {
        while (peek()?.let(keep) == true) appendCodePoint(bump()!!)
    }

    private fun identStart(c: Int) = c < 128 && (Character.isLetter(c) || c == '_'.code || c == '$'.code)

    private fun identChar(c: Int) = c < 128 && (Character.isLetterOrDigit(c) || c == '_'.code || c == '$'.code)

    /** Every token. */
    fun tokens(): List<Token> {
        val out = mutableListOf<Token>()
        while (true) {
            val c = peek() ?: return out
            val at = pos()
            if (c.toChar() in " \t\r\n") {
                bump()
                continue
            }
            out.add(Token(token(c, at), at))
        }
    }

    private fun token(c: Int, at: Pos): Tok {
        refusal(c)?.let { throw Refusal.At(it, at) }
        return when {
            c == '"'.code || c == '\''.code -> Tok.Str(string(at))
            c == '-'.code || c in '0'.code..'9'.code -> Tok.Num(number(at))
            identStart(c) -> Tok.Ident(takeWhile(::identChar))
            c < 128 && c.toChar() in "{}[](),:=." -> Tok.Punct(bump()!!)
            else -> throw Refusal.At("syntax", at)
        }
    }

    /** A comment or a template literal: what the subset refuses at the first character. */
    private fun refusal(c: Int): String? = when {
        c == '/'.code && (peek(1) == '/'.code || peek(1) == '*'.code) -> "comment"
        c == '`'.code -> "text"
        else -> null
    }

    private fun string(at: Pos): String {
        val quote = bump()!!
        val out = StringBuilder()
        while (true) {
            val c = bump()
            when {
                c == null || c.toChar() in "\n\r\t" -> throw Refusal.At("text", at)
                c == quote -> return out.toString()
                c == '\\'.code -> out.appendCodePoint(escape(quote) ?: throw Refusal.At("text", at))
                c == '$'.code && peek() == '{'.code -> throw Refusal.At("text", at)
                else -> out.appendCodePoint(c)
            }
        }
    }

    /** JSON's escapes, and `\'` inside single quotes. */
    private fun escape(quote: Int): Int? {
        val c = bump() ?: return null
        if (c == 'u'.code) return unicode()
        if (c == '\''.code) return c.takeIf { quote == '\''.code }
        return SIMPLE_ESCAPES[c]
    }

    /** `\uXXXX`: four hex digits naming a scalar value, never a lone surrogate. */
    private fun unicode(): Int? {
        val hex = (0 until 4).mapNotNull { bump() }
        val value = String(hex.toIntArray(), 0, hex.size).toIntOrNull(16) ?: return null
        return value.takeIf { it >= 0 && it !in 0xD800..0xDFFF }
    }

    /** A finite JSON number: `-?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?`. */
    private fun number(at: Pos): JsonNode {
        val text = takeWhile { it < 128 && (Character.isLetterOrDigit(it) || it.toChar() in "-+.") }
        if (!NUMBER.matches(text)) throw Refusal.At("number", at)
        val n = Json.parse(text)?.takeIf { it.isNumber } ?: throw Refusal.At("number", at)
        if (!n.isIntegralNumber && !n.asDouble().isFinite()) throw Refusal.At("number", at)
        return n
    }
}
