package com.getlingara.cli.java.core;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Contract B2's refusal on stderr: {@code error: <text> (<code>)} and any advice lines, or under
 * {@code --json} one JSON line. What a part-way create made is named either way (16.10.26ad D2),
 * so nothing it made is left unnamed.
 */
public final class Errors {
    private Errors() {}

    /** The lines {@code fail} prints on stderr. */
    public static List<String> lines(Fail fail, boolean json) {
        List<String> out = new ArrayList<>();
        if (json) {
            out.add(Json.write(jsonLine(fail)));
            return out;
        }
        out.add("error: " + fail.text() + " (" + fail.code() + ")");
        out.addAll(fail.hints());
        fail.made().forEach(m -> out.add("made: " + m.kind() + " " + m.id()));
        return out;
    }

    private static ObjectNode jsonLine(Fail fail) {
        ObjectNode error = Json.object();
        error.put("code", fail.code());
        error.put("message", fail.text());
        error.put("exit", fail.exit().code());
        if (!fail.hints().isEmpty()) {
            error.set("hints", Json.array(fail.hints()));
        }
        if (!fail.made().isEmpty()) {
            ArrayNode made = error.putArray("made");
            fail.made().forEach(m -> made.addObject().put("kind", m.kind()).put("id", m.id()));
        }
        ObjectNode line = Json.object();
        line.set("error", error);
        return line;
    }
}
