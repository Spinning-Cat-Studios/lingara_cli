// Statements and values over the tokens: `const <handle> =
// lingara.<kind>(<value>)`, one after another. A second call argument is a
// script, which refuses the file there and then.
package com.getlingara.cli.kotlin.literal

private val KINDS = setOf("client", "secret", "webhook", "app")

class Parser(private val toks: List<Token>, private val end: Pos) {
    private var i = 0
    private val handles = mutableSetOf<String>()

    private fun peek(): Token? = toks.getOrNull(i)

    private fun next(): Token = (peek() ?: throw Refusal.At("syntax", end)).also { i++ }

    private fun isPunct(t: Token?, c: Char) = t?.tok == Tok.Punct(c.code)

    private fun punct(c: Char): Pos = next().let { t -> if (isPunct(t, c)) t.at else throw Refusal.At("syntax", t.at) }

    private fun ident(): Pair<String, Pos> = next().let { t -> (t.tok as? Tok.Ident)?.let { it.s to t.at } ?: throw Refusal.At("syntax", t.at) }

    private fun word(want: String): Pos = ident().let { (s, at) -> if (s == want) at else throw Refusal.At("syntax", at) }

    /** Every statement of the body, which ends at [end]. */
    fun statements(): List<Statement> {
        val out = mutableListOf<Statement>()
        while (peek() != null) out.add(statement())
        return out
    }

    private fun statement(): Statement {
        val at = word("const")
        val (handle, handleAt) = ident()
        if (handle in handles) throw Refusal.At("handle", handleAt)
        punct('=')
        word("lingara")
        punct('.')
        val (kind, kindAt) = ident()
        if (kind !in KINDS) throw Refusal.At("syntax", kindAt)
        punct('(')
        val value = value()
        if (isPunct(peek(), ',')) throw Refusal.Scripted(handle)
        punct(')')
        // Declared only now: a value may name the statements above it, never its own.
        handles.add(handle)
        return Statement(handle, kind, value, at)
    }

    private fun value(): Lit {
        val t = next()
        return when (val tok = t.tok) {
            is Tok.Str -> Lit.Str(tok.s)
            is Tok.Num -> Lit.Num(tok.n)
            is Tok.Ident -> word(tok.s, t.at)
            is Tok.Punct -> when (tok.c) {
                '['.code -> Lit.Arr(items(']') { value() })
                '{'.code -> Lit.Obj(obj(), t.at)
                else -> throw Refusal.At("syntax", t.at)
            }
        }
    }

    private fun word(w: String, at: Pos): Lit = when {
        w == "true" || w == "false" -> Lit.Bool(w == "true")
        w == "null" -> Lit.Null
        w in handles -> Lit.Ref(w)
        else -> throw Refusal.At("reference", at)
    }

    /** `item {, item} [,] close`, the opener already read. */
    private fun <T> items(close: Char, item: () -> T): List<T> {
        val out = mutableListOf<T>()
        while (true) {
            if (isPunct(peek(), close)) {
                i++
                return out
            }
            out.add(item())
            val t = next()
            if (isPunct(t, close)) return out
            if (!isPunct(t, ',')) throw Refusal.At("syntax", t.at)
        }
    }

    private fun obj(): List<Member> {
        val seen = mutableSetOf<String>()
        return items('}') {
            val t = next()
            val key = when (val tok = t.tok) {
                is Tok.Ident -> tok.s
                is Tok.Str -> tok.s
                else -> throw Refusal.At("syntax", t.at)
            }
            if (!seen.add(key)) throw Refusal.At("key", t.at)
            punct(':')
            Member(key, t.at, value())
        }
    }
}
