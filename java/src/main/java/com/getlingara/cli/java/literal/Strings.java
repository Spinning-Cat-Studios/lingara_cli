package com.getlingara.cli.java.literal;

/**
 * A quoted string of the literal subset: JSON's escapes (and {@code \'} inside single quotes), no
 * raw tab or line break, and no dollar-brace, which would make it a template.
 */
final class Strings {
    private Strings() {}

    static String read(Lexer lx, Lit.Pos at) {
        int quote = lx.nextChar();
        StringBuilder out = new StringBuilder();
        while (true) {
            int c = lx.nextChar();
            if (raw(c)) {
                throw Refusal.at("text", at);
            }
            if (c == quote) {
                return out.toString();
            }
            if (c == '\\') {
                int escaped = escape(lx, quote);
                if (escaped < 0) {
                    throw Refusal.at("text", at);
                }
                out.appendCodePoint(escaped);
            } else if (c == '$' && lx.peekChar() == '{') {
                throw Refusal.at("text", at);
            } else {
                out.appendCodePoint(c);
            }
        }
    }

    /** The end of input, or a character a string may not hold raw. */
    private static boolean raw(int c) {
        return c < 0 || c == '\n' || c == '\r' || c == '\t';
    }

    /** JSON's one-letter escapes, each beside the character it stands for. */
    private static final String ESCAPED = "\"\\/bfnrt";
    private static final String MEANS = "\"\\/\b\f\n\r\t";

    /** One escape's character; -1 when it is not one JSON knows. */
    private static int escape(Lexer lx, int quote) {
        int c = lx.nextChar();
        if (c == 'u') {
            return unicode(lx);
        }
        if (c == '\'') {
            return quote == '\'' ? '\'' : -1;
        }
        int at = c < 0 ? -1 : ESCAPED.indexOf(c);
        return at < 0 ? -1 : MEANS.charAt(at);
    }

    private static int unicode(Lexer lx) {
        StringBuilder hex = new StringBuilder();
        for (int k = 0; k < 4; k++) {
            int c = lx.nextChar();
            if (c >= 0) {
                hex.appendCodePoint(c);
            }
        }
        try {
            int value = Integer.parseInt(hex.toString(), 16);
            // A lone surrogate is no character, as the reference kit's char::from_u32 says.
            return value >= 0xD800 && value <= 0xDFFF ? -1 : value;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
