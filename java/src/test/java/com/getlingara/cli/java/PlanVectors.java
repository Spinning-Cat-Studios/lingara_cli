package com.getlingara.cli.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.cli.java.canvas.Argv;
import com.getlingara.cli.java.canvas.Definition;
import com.getlingara.cli.java.canvas.Plan;
import com.getlingara.cli.java.core.Json;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code conformance/vectors/canvas-plans.json}, which every kit's planner is held to
 * (16.10.26ad AC8): the kept records, the ops, and the steps or stop for each vector's verdicts.
 */
final class PlanVectors {
    private PlanVectors() {}

    private static Map<String, String> records(JsonNode v) {
        Map<String, String> out = new TreeMap<>();
        v.properties().forEach(e -> out.put(e.getKey(), e.getValue().path("id").asText()));
        return out;
    }

    private static List<Plan.Row> rows(JsonNode v) {
        List<Plan.Row> out = new ArrayList<>();
        v.forEach(r -> out.add(Plan.Row.read(r)));
        return out;
    }

    private static Plan.Live live(JsonNode v) {
        return new Plan.Live(rows(v.path("oauth_client")), rows(v.path("webhook_out")), rows(v.path("app")));
    }

    private static JsonNode rendered(Argv.Step step) {
        ObjectNode out = Json.object();
        out.put("handle", step.handle());
        out.put("action", step.action());
        out.put("records", step.records());
        ArrayNode argv = out.putArray("argv");
        for (Argv.Part p : step.argv()) {
            argv.add(switch (p.kind()) {
                case Argv.Part.MADE -> "<" + p.value() + ">";
                case Argv.Part.MANIFEST -> "<" + p.value() + ".manifest>";
                default -> p.value();
            });
        }
        return out;
    }

    static void check() throws IOException {
        JsonNode file = Json.parse(Files.readString(Fakes.repo().resolve("conformance/vectors/canvas-plans.json")));
        assertTrue(file.path("vectors").size() >= 8, "every rule has its vector");
        for (JsonNode v : file.path("vectors")) {
            vector(v);
        }
        List<Argv.Part> parts = List.of(new Argv.Part(Argv.Part.TEXT, "oauth_client"), new Argv.Part(Argv.Part.TEXT, "--name"),
                new Argv.Part(Argv.Part.TEXT, "My server's"), new Argv.Part(Argv.Part.MADE, "n1"));
        assertEquals("lingara oauth_client --name 'My server'\\''s' '<n1>'", Argv.shellLine(parts, h -> ""));
    }

    private static void vector(JsonNode v) {
        String name = v.path("name").asText();
        Definition def = Definition.read(v.path("definition"));
        Plan.Live rows = live(v.path("live"));
        Map<String, String> kept = Plan.stillLive(def, records(v.path("recorded")), rows);
        List<Plan.Op> ops;
        try {
            ops = Plan.build(def, kept);
        } catch (Plan.Refusal r) {
            assertEquals("script", r.why, name);
            assertEquals(v.path("expect").path("refused").asText(), r.node, name);
            return;
        }
        ObjectNode keptJson = Json.object();
        kept.forEach(keptJson::put);
        assertEquals(v.path("expect").path("kept"), keptJson, name + ": kept");
        ArrayNode opsJson = Json.array();
        ops.forEach(op -> opsJson.add(op.wire()));
        assertEquals(v.path("expect").path("ops"), opsJson, name + ": ops");
        if (!v.path("verdicts").isArray()) {
            return;
        }
        List<JsonNode> verdicts = new ArrayList<>();
        v.path("verdicts").forEach(verdicts::add);
        try {
            ArrayNode steps = Json.array();
            Argv.steps(ops, verdicts, rows, kept).forEach(s -> steps.add(rendered(s)));
            assertEquals(v.path("steps"), steps, name + ": steps");
        } catch (Argv.Stop stop) {
            ObjectNode want = Json.object();
            if (stop.replace != null) {
                want.put("replace", stop.replace);
            } else {
                ArrayNode refused = want.putArray("refused");
                stop.refused.forEach(r -> refused.add(r[0]));
            }
            assertEquals(v.path("stop"), want, name + ": stop");
        }
    }
}
