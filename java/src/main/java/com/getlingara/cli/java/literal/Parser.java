package com.getlingara.cli.java.literal;

import com.getlingara.cli.java.literal.Lexer.Token;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Statements and values over the tokens: {@code const <handle> = lingara.<kind>(<value>)}, one
 * after another. A second call argument is a script, which refuses the file there and then.
 */
final class Parser {
    private static final Set<String> KINDS = Set.of("client", "secret", "webhook", "app");

    /** One statement: its handle, kind, value and where {@code const} is. */
    record Statement(String handle, String kind, Lit value, Lit.Pos at) {}

    private final List<Token> toks;
    private int i;
    private final Set<String> handles = new HashSet<>();
    private final Lit.Pos end;

    private Parser(List<Token> toks, Lit.Pos end) {
        this.toks = toks;
        this.end = end;
    }

    /** Every statement of the body, which ends at {@code end}. */
    static List<Statement> statements(List<Token> toks, Lit.Pos end) {
        Parser p = new Parser(toks, end);
        List<Statement> out = new ArrayList<>();
        while (p.peek() != null) {
            out.add(p.statement());
        }
        return out;
    }

    private Token peek() {
        return i < toks.size() ? toks.get(i) : null;
    }

    private Token next() {
        Token t = peek();
        if (t == null) {
            throw Refusal.at("syntax", end);
        }
        i++;
        return t;
    }

    private void punct(char c) {
        Token t = next();
        if (!t.isPunct(c)) {
            throw Refusal.at("syntax", t.at());
        }
    }

    private Token ident() {
        Token t = next();
        if (!t.kind().equals("ident")) {
            throw Refusal.at("syntax", t.at());
        }
        return t;
    }

    private Lit.Pos word(String want) {
        Token t = ident();
        if (!t.text().equals(want)) {
            throw Refusal.at("syntax", t.at());
        }
        return t.at();
    }

    private Statement statement() {
        Lit.Pos at = word("const");
        Token handle = ident();
        if (handles.contains(handle.text())) {
            throw Refusal.at("handle", handle.at());
        }
        punct('=');
        word("lingara");
        punct('.');
        Token kind = ident();
        if (!KINDS.contains(kind.text())) {
            throw Refusal.at("syntax", kind.at());
        }
        punct('(');
        Lit value = value();
        if (peek() != null && peek().isPunct(',')) {
            throw Refusal.scripted(handle.text());
        }
        punct(')');
        // Declared only now: a value may name the statements above it, never its own.
        handles.add(handle.text());
        return new Statement(handle.text(), kind.text(), value, at);
    }

    private Lit value() {
        Token t = next();
        switch (t.kind()) {
            case "str":
                return Lit.str(t.text());
            case "num":
                return Lit.num(t.number());
            case "ident":
                return identValue(t);
            default:
                if (t.isPunct('[')) {
                    return Lit.arr(items(']', this::value));
                }
                if (t.isPunct('{')) {
                    return Lit.obj(object(), t.at());
                }
                throw Refusal.at("syntax", t.at());
        }
    }

    private Lit identValue(Token t) {
        String w = t.text();
        if (w.equals("true") || w.equals("false")) {
            return Lit.bool(w.equals("true"));
        }
        if (w.equals("null")) {
            return Lit.nul();
        }
        if (handles.contains(w)) {
            return Lit.ref(w);
        }
        throw Refusal.at("reference", t.at());
    }

    /** {@code item {, item} [,] close}, the opener already read. */
    private <T> List<T> items(char close, Supplier<T> item) {
        List<T> out = new ArrayList<>();
        while (true) {
            if (peek() != null && peek().isPunct(close)) {
                i++;
                return out;
            }
            out.add(item.get());
            Token t = next();
            if (t.isPunct(close)) {
                return out;
            }
            if (!t.isPunct(',')) {
                throw Refusal.at("syntax", t.at());
            }
        }
    }

    private List<Lit.Member> object() {
        Set<String> seen = new HashSet<>();
        return items('}', () -> {
            Token key = next();
            if (!key.kind().equals("ident") && !key.kind().equals("str")) {
                throw Refusal.at("syntax", key.at());
            }
            if (!seen.add(key.text())) {
                throw Refusal.at("key", key.at());
            }
            punct(':');
            return new Lit.Member(key.text(), key.at(), value());
        });
    }
}
