package com.getlingara.cli.java.literal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/**
 * One literal value of the subset (spec/connection-literal.md): a string, a finite number, a
 * boolean, {@code null}, an array, an object (with where its opening brace is), or a reference to a
 * statement declared above.
 */
public record Lit(Kind kind, String text, JsonNode number, boolean bool, List<Lit> items, List<Member> members, Pos at) {
    public enum Kind { STR, NUM, BOOL, NULL, ARR, OBJ, REF }

    /** One object member: its key, where the key is, and its value. */
    public record Member(String key, Pos at, Lit value) {}

    /** A position, 1-based. */
    public record Pos(int line, int column) {}

    static Lit str(String s) {
        return new Lit(Kind.STR, s, null, false, null, null, null);
    }

    static Lit ref(String handle) {
        return new Lit(Kind.REF, handle, null, false, null, null, null);
    }

    static Lit num(JsonNode n) {
        return new Lit(Kind.NUM, null, n, false, null, null, null);
    }

    static Lit bool(boolean b) {
        return new Lit(Kind.BOOL, null, null, b, null, null, null);
    }

    static Lit nul() {
        return new Lit(Kind.NULL, null, null, false, null, null, null);
    }

    static Lit arr(List<Lit> items) {
        return new Lit(Kind.ARR, null, null, false, items, null, null);
    }

    static Lit obj(List<Member> members, Pos at) {
        return new Lit(Kind.OBJ, null, null, false, null, members, at);
    }

    /** As JSON; a reference becomes its handle. */
    public JsonNode toJson() {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        return switch (kind) {
            case STR, REF -> nodes.textNode(text);
            case NUM -> number;
            case BOOL -> nodes.booleanNode(bool);
            case NULL -> nodes.nullNode();
            case ARR -> {
                ArrayNode out = nodes.arrayNode();
                items.forEach(i -> out.add(i.toJson()));
                yield out;
            }
            case OBJ -> {
                ObjectNode out = nodes.objectNode();
                members.forEach(m -> out.set(m.key(), m.value().toJson()));
                yield out;
            }
        };
    }

    /** The member named {@code key}, or {@code null}. */
    public Lit member(String key) {
        if (members == null) {
            return null;
        }
        for (Member m : members) {
            if (m.key().equals(key)) {
                return m.value();
            }
        }
        return null;
    }
}
