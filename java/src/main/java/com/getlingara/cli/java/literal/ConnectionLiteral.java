package com.getlingara.cli.java.literal;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * The connection literal subset (ADR 16.10.26ae D2; spec/connection-literal.md): {@code
 * lingara/connection.js} read without evaluating JavaScript. {@link Lexer} makes the tokens,
 * {@link Parser} the statements, {@link ManifestConvert} turns the one {@code lingara.app}
 * statement's {@code manifest} into the wire manifest. Every refusal is a {@link Refusal}.
 */
public final class ConnectionLiteral {
    /** The file's first line, byte for byte. */
    public static final String HEADER = "// lingara connection v1 (schema 2)";

    private ConnectionLiteral() {}

    /** Every statement's kind, in order; a refusal when the file is not the subset. */
    public static List<String> statementKinds(String text) {
        List<String> out = new ArrayList<>();
        statements(text).forEach(s -> out.add(s.kind()));
        return out;
    }

    static List<Parser.Statement> statements(String text) {
        int nl = text.indexOf('\n');
        String header = nl < 0 ? text : text.substring(0, nl);
        String body = nl < 0 ? "" : text.substring(nl + 1);
        if (!stripCr(header).equals(HEADER)) {
            throw Refusal.at("header", new Lit.Pos(1, 1));
        }
        int lines = Math.max(1, (int) text.lines().count());
        return Parser.statements(Lexer.tokens(body, 2), new Lit.Pos(lines + 1, 1));
    }

    private static String stripCr(String line) {
        String out = line;
        while (out.endsWith("\r")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    /** The wire manifest of the file's one {@code lingara.app} statement. */
    public static JsonNode appManifest(String text) {
        List<Parser.Statement> apps = new ArrayList<>();
        statements(text).forEach(s -> {
            if (s.kind().equals("app")) {
                apps.add(s);
            }
        });
        if (apps.isEmpty()) {
            throw Refusal.at("app_count", new Lit.Pos(1, 1));
        }
        if (apps.size() > 1) {
            throw Refusal.at("app_count", apps.get(1).at());
        }
        Parser.Statement app = apps.get(0);
        if (app.value().kind() != Lit.Kind.OBJ) {
            throw Refusal.at("syntax", app.at());
        }
        for (Lit.Member m : app.value().members()) {
            if (!m.key().equals("client") && !m.key().equals("manifest")) {
                throw Refusal.at("option", m.at());
            }
        }
        Lit manifest = app.value().member("manifest");
        if (manifest == null || manifest.kind() != Lit.Kind.OBJ) {
            throw Refusal.at("manifest_key", app.value().at());
        }
        return ManifestConvert.convert(manifest.members(), manifest.at());
    }
}
