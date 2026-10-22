package com.getlingara.cli.java.canvas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.auth.Calls;
import com.getlingara.cli.java.commands.Canvases;
import com.getlingara.cli.java.commands.Kind;
import com.getlingara.cli.java.commands.Lists;
import com.getlingara.cli.java.commands.Objects;
import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Json;
import com.getlingara.cli.java.http.Request;
import com.getlingara.cli.java.http.Routes.Fixed;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code canvas apply <id>} (ADR 16.10.26ad D7; contract B7): a draft creates nothing; apply plans
 * in the web planner's order, dry-runs the plan, then runs <strong>this CLI's own verbs</strong>
 * in-process, one per op ({@link Execute}), records each object's {@code runtime_id} and
 * activates. {@code --dry-run} prints the verdicts and {@code --print-commands} the argvs; neither
 * writes.
 */
public final class Apply {
    private static final String[] VERDICT_COLUMNS = {
        "HANDLE", "handle", "KIND", "kind", "OP", "op", "VERDICT", "verdict", "CODE", "code"
    };

    private Apply() {}

    /** What one apply works over: the draft, its record and the live lists. */
    record Planned(String canvasId, long draftVersion, Map<String, String> kept, Plan.Live live, List<Plan.Op> ops) {}

    static Fail refused(String message) {
        return new Fail(Exit.REFUSED, "canvas_apply_refused", message);
    }

    /** The active record overlaid by the draft's, kept to the draft's nodes ({@code recordedObjects}). */
    private static Map<String, String> recorded(JsonNode canvas, Definition def) {
        Map<String, String> all = new TreeMap<>();
        for (String side : new String[] {"active", "draft"}) {
            canvas.path(side).path("objects").properties().forEach(e -> all.put(e.getKey(), Json.text(e.getValue(), "id", "")));
        }
        all.keySet().removeIf(h -> def.nodes().stream().noneMatch(n -> n.id().equals(h)));
        return all;
    }

    private static List<Plan.Row> rows(List<JsonNode> items) {
        List<Plan.Row> out = new ArrayList<>();
        items.forEach(i -> out.add(Plan.Row.read(i)));
        return out;
    }

    private static Fail refusalFail(Plan.Refusal r) {
        return switch (r.why) {
            case "ephemeral" -> Fail.message(Exit.RENDER_URL_EPHEMERAL, "render_url_ephemeral", "file", "node " + r.node + "'s manifest");
            case "script" -> refused("node " + r.node + " runs a script; apply this canvas in the browser");
            case "fields" -> refused("node " + r.node + "'s fields are not one the builder can apply: " + r.detail);
            case "no_client" -> refused("node " + r.node + " needs exactly one client node upstream");
            default -> refused("the canvas's edges form a cycle");
        };
    }

    /** D7 steps 1 and 2: the canvas and the three lists, every page, then the plan. */
    static Planned plan(Ctx ctx, String id) {
        JsonNode canvas = Canvases.get(ctx, id);
        if (Json.isNull(canvas.path("draft"))) {
            throw refused("nothing to apply: save a draft with `lingara canvas draft`");
        }
        Plan.Live live = new Plan.Live(
                rows(Objects.list(ctx, Kind.OAUTH_CLIENT)), rows(Objects.list(ctx, Kind.WEBHOOK_OUTBOUND)), rows(Objects.list(ctx, Kind.APP)));
        Definition definition = Definition.read(canvas.path("draft").path("definition"));
        Map<String, String> kept = Plan.stillLive(definition, recorded(canvas, definition), live);
        List<Plan.Op> ops;
        try {
            ops = Plan.build(definition, kept);
        } catch (Plan.Refusal r) {
            throw refusalFail(r);
        }
        JsonNode version = canvas.path("draft").path("draft_version");
        return new Planned(id, version.isIntegralNumber() ? version.asLong() : 0, kept, live, ops);
    }

    public static void run(Ctx ctx, String id, boolean dryRun, boolean printCommands) {
        Planned planned = plan(ctx, id);
        ObjectNode body = Json.object();
        ArrayNode wire = body.putArray("ops");
        planned.ops().forEach(op -> wire.add(op.wire()));
        JsonNode answer = Calls.call(ctx, Request.of(Fixed.CANVAS_DRY_RUN.route()).withJson(body));
        List<JsonNode> verdicts = new ArrayList<>();
        answer.path("verdicts").forEach(verdicts::add);
        if (dryRun) {
            printVerdicts(ctx, answer, verdicts);
            if (answer.path("ok").isBoolean() && !answer.path("ok").booleanValue()) {
                throw refused("the dry-run refused at least one op");
            }
            return;
        }
        List<Argv.Step> steps = steps(planned, verdicts);
        Map<String, Path> manifests = writeManifests(planned.ops());
        try {
            if (printCommands) {
                printCommands(ctx, steps, manifests);
            } else {
                new Execute(planned).run(ctx, steps, manifests);
            }
        } finally {
            if (!printCommands) {
                manifests.values().forEach(Apply::remove);
            }
        }
    }

    private static List<Argv.Step> steps(Planned planned, List<JsonNode> verdicts) {
        try {
            return Argv.steps(planned.ops(), verdicts, planned.live(), planned.kept());
        } catch (Argv.Stop stop) {
            if (stop.replace != null) {
                throw refused("node " + stop.replace + ": unexpected `would_replace` from the server; nothing was applied");
            }
            Fail fail = refused("the dry-run refused " + stop.refused.size() + " op(s); nothing was applied");
            stop.refused.forEach(r -> fail.hint(r[0] + ": " + r[2] + " (" + r[1] + ")"));
            throw fail;
        }
    }

    private static void remove(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            // A temp file left behind holds a manifest, never a secret.
        }
    }

    private static void printVerdicts(Ctx ctx, JsonNode answer, List<JsonNode> verdicts) {
        if (ctx.out.json()) {
            ctx.out.jsonValue(answer);
        } else {
            Lists.printRows(ctx, verdicts, VERDICT_COLUMNS);
        }
    }

    /** Each app op's manifest, as the file its {@code --manifest} names. */
    private static Map<String, Path> writeManifests(List<Plan.Op> ops) {
        Map<String, Path> out = new TreeMap<>();
        for (Plan.Op op : ops) {
            if (!op.kind().equals("app")) {
                continue;
            }
            Path path = Path.of(System.getProperty("java.io.tmpdir"), "lingara-" + ProcessHandle.current().pid() + "-" + op.handle() + ".manifest.json");
            JsonNode manifest = op.body() == null ? Json.object().nullNode() : op.body().path("manifest");
            try {
                Files.writeString(path, Json.write(manifest), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw Fail.failure(path + ": " + e.getMessage());
            }
            out.put(op.handle(), path);
        }
        return out;
    }

    /** The matryoshka made visible: what apply would run, as shell lines. */
    private static void printCommands(Ctx ctx, List<Argv.Step> steps, Map<String, Path> manifests) {
        List<String> lines = new ArrayList<>();
        for (Argv.Step s : steps) {
            if (!s.argv().isEmpty()) {
                lines.add(Argv.shellLine(s.argv(), h -> manifests.containsKey(h) ? manifests.get(h).toString() : ""));
            }
        }
        if (ctx.out.json()) {
            ctx.out.jsonValue(Json.array(lines));
        } else {
            lines.forEach(line -> ctx.out.print(line + "\n"));
        }
    }
}
