package com.getlingara.cli.java.literal;

import com.fasterxml.jackson.databind.JsonNode;
import com.getlingara.cli.java.core.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The literal subset's tokens (spec/connection-literal.md): identifiers, JSON-escaped strings in
 * either quote, finite JSON numbers and punctuation, each with its 1-based line and column. A
 * comment, a template literal or a character the subset does not know stops the scan.
 */
final class Lexer {
    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");
    private static final String PUNCT = "{}[](),:=.";

    /** {@code kind} is {@code ident}, {@code str}, {@code num} or {@code punct}. */
    record Token(String kind, String text, JsonNode number, Lit.Pos at) {
        boolean isPunct(char c) {
            return kind.equals("punct") && text.charAt(0) == c;
        }
    }

    private final int[] chars;
    private int i;
    private int line;
    private int col = 1;

    private Lexer(String text, int firstLine) {
        this.chars = text.codePoints().toArray();
        this.line = firstLine;
    }

    private Lit.Pos pos() {
        return new Lit.Pos(line, col);
    }

    private int peek(int ahead) {
        return i + ahead < chars.length ? chars[i + ahead] : -1;
    }

    private int bump() {
        if (i >= chars.length) {
            return -1;
        }
        int c = chars[i++];
        if (c == '\n') {
            line++;
            col = 1;
        } else {
            col++;
        }
        return c;
    }

    private static boolean identStart(int c) {
        return c < 128 && (Character.isLetter(c) || c == '_' || c == '$');
    }

    private static boolean identChar(int c) {
        return c < 128 && (Character.isLetterOrDigit(c) || c == '_' || c == '$');
    }

    private String takeWhile(java.util.function.IntPredicate keep) {
        StringBuilder out = new StringBuilder();
        while (peek(0) >= 0 && keep.test(peek(0))) {
            out.appendCodePoint(bump());
        }
        return out.toString();
    }

    /** Every token from {@code text}, which starts at line {@code firstLine}. */
    static List<Token> tokens(String text, int firstLine) {
        Lexer lx = new Lexer(text, firstLine);
        List<Token> out = new ArrayList<>();
        while (lx.peek(0) >= 0) {
            Token token = lx.next();
            if (token != null) {
                out.add(token);
            }
        }
        return out;
    }

    /** The next token; {@code null} for whitespace. */
    private Token next() {
        int c = peek(0);
        Lit.Pos at = pos();
        if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
            bump();
            return null;
        }
        if (c == '/' && (peek(1) == '/' || peek(1) == '*')) {
            throw Refusal.at("comment", at);
        }
        if (c == '`') {
            throw Refusal.at("text", at);
        }
        return token(c, at);
    }

    /** A string, number, identifier or punctuation mark starting at {@code c}. */
    private Token token(int c, Lit.Pos at) {
        if (c == '"' || c == '\'') {
            return new Token("str", Strings.read(this, at), null, at);
        }
        if (c == '-' || (c >= '0' && c <= '9')) {
            return new Token("num", null, number(at), at);
        }
        if (identStart(c)) {
            return new Token("ident", takeWhile(Lexer::identChar), null, at);
        }
        if (c < 128 && PUNCT.indexOf(c) >= 0) {
            bump();
            return new Token("punct", Character.toString(c), null, at);
        }
        throw Refusal.at("syntax", at);
    }

    /** A finite JSON number: {@code -?(0|[1-9]\d*)(\.\d+)?([eE][+-]?\d+)?}. */
    private JsonNode number(Lit.Pos at) {
        String text = takeWhile(c -> c < 128 && (Character.isLetterOrDigit(c) || c == '-' || c == '+' || c == '.'));
        JsonNode n = NUMBER.matcher(text).matches() ? Json.parse(text) : null;
        if (n == null || !n.isNumber() || (n.isDouble() && !Double.isFinite(n.doubleValue()))) {
            throw Refusal.at("number", at);
        }
        return n;
    }

    /** The string reader's view of the scan: one code point at a time. */
    int nextChar() {
        return bump();
    }

    int peekChar() {
        return peek(0);
    }
}
