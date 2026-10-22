package com.getlingara.cli.java.canvas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.Outcome;
import com.getlingara.cli.java.auth.Calls;
import com.getlingara.cli.java.commands.Kind;
import com.getlingara.cli.java.commands.Objects;
import com.getlingara.cli.java.core.Assets;
import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Json;
import com.getlingara.cli.java.core.Table;
import com.getlingara.cli.java.http.Request;
import com.getlingara.cli.java.http.Routes.Fixed;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 16.10.26ad D7 steps 4–7. Each step's argv is dispatched <strong>in-process</strong> through
 * {@code App.run}, {@code Main}'s own entry point, with {@code --json}, so no credential crosses a
 * process boundary and no second token exchange happens. Apply records each object's {@code
 * runtime_id} (a secret's id), never its platform object id, then activates {@code complete}, or
 * {@code partial} at the first failure.
 */
final class Execute {
    /** A {@code runtime_id} still {@code null} is re-read once a second, ten times (D7). */
    private static final int PUBLISH_POLLS = 10;

    private final Apply.Planned planned;
    private final Map<String, String> recorded;
    /** Handle → the platform object id an earlier step made. */
    private final Map<String, String> made = new TreeMap<>();
    /** Handle → the secret its client's create minted. */
    private final Map<String, String> minted = new TreeMap<>();
    private final List<ObjectNode> secrets = new ArrayList<>();
    private final List<List<String>> rows = new ArrayList<>();

    Execute(Apply.Planned planned) {
        this.planned = planned;
        this.recorded = new TreeMap<>(planned.kept());
    }

    void run(Ctx ctx, List<Argv.Step> steps, Map<String, Path> manifests) {
        for (int done = 0; done < steps.size(); done++) {
            try {
                step(ctx, steps.get(done), manifests);
            } catch (Fail fail) {
                throw partial(ctx, done, steps.size(), fail);
            }
        }
        activate(ctx, "complete");
        report(ctx, "complete");
    }

    private void step(Ctx ctx, Argv.Step step, Map<String, Path> manifests) {
        if (step.records().equals("client_secret")) {
            String id = minted.get(step.client());
            if (id == null) {
                throw Fail.failure(step.client() + " minted no secret");
            }
            secrets.stream().filter(s -> step.client().equals(s.path("handle").asText(null))).forEach(s -> s.put("handle", step.handle()));
            recorded.put(step.handle(), id);
        } else if (!step.argv().isEmpty()) {
            JsonNode answer = invoke(ctx, step.handle(), resolve(step.argv(), manifests));
            record(ctx, step, answer);
        }
        rows.add(List.of(step.handle(), step.kind(), step.action(), recorded.getOrDefault(step.handle(), "-")));
    }

    private List<String> resolve(List<Argv.Part> argv, Map<String, Path> manifests) {
        List<String> out = new ArrayList<>();
        for (Argv.Part p : argv) {
            switch (p.kind()) {
                case Argv.Part.MADE -> out.add(required(made.get(p.value()), p.value() + " was not made"));
                case Argv.Part.MANIFEST -> {
                    Path path = manifests.get(p.value());
                    out.add(required(path == null ? null : path.toString(), "no manifest for " + p.value()));
                }
                default -> out.add(p.value());
            }
        }
        return out;
    }

    private static String required(String value, String missing) {
        if (value == null) {
            throw Fail.failure(missing);
        }
        return value;
    }

    /** One inner verb; its JSON, any secret it revealed kept even when it failed part-way. */
    private JsonNode invoke(Ctx ctx, String handle, List<String> argv) {
        List<String> full = new ArrayList<>(argv);
        full.addAll(List.of("--json", "--profile", ctx.profile));
        Outcome outcome = ctx.app.run(full, new PrintStream(OutputStream.nullOutputStream()));
        JsonNode answer = Json.parse(outcome.stdout());
        answer = answer == null ? Json.object().nullNode() : answer;
        ObjectNode secret = null;
        if (answer.path("secret").isObject()) {
            secret = secretRow(handle, answer.path("secret").path("id"), answer.path("secret").path("value"));
        } else if (answer.path("secret_id").isTextual()) {
            secret = secretRow(handle, answer.path("secret_id"), answer.path("secret"));
        }
        if (secret != null) {
            minted.put(handle, Json.text(secret, "id", ""));
            secrets.add(secret);
        }
        if (outcome.fail() != null) {
            throw outcome.fail();
        }
        return answer;
    }

    private static ObjectNode secretRow(String handle, JsonNode id, JsonNode value) {
        ObjectNode row = Json.object();
        row.put("handle", handle);
        row.set("id", id.isMissingNode() ? Json.object().nullNode() : id);
        row.set("value", value.isMissingNode() ? Json.object().nullNode() : value);
        return row;
    }

    private void record(Ctx ctx, Argv.Step step, JsonNode answer) {
        if (step.records().equals("runtime_id")) {
            String platformId = Json.text(answer, "id", "");
            made.put(step.handle(), platformId);
            recorded.put(step.handle(), runtimeId(ctx, kindOf(step.kind()), platformId, answer));
        } else if (step.records().equals("secret_id")) {
            recorded.put(step.handle(), Json.text(answer, "secret_id", ""));
        }
    }

    private static Kind kindOf(String nodeKind) {
        return switch (nodeKind) {
            case "client" -> Kind.OAUTH_CLIENT;
            case "webhook" -> Kind.WEBHOOK_OUTBOUND;
            default -> Kind.APP;
        };
    }

    /** The object's {@code runtime_id}, set once publishing has projected (16.10.26v §5). */
    private static String runtimeId(Ctx ctx, Kind k, String platformId, JsonNode answer) {
        JsonNode current = answer;
        for (int i = 0; i < PUBLISH_POLLS; i++) {
            if (Json.text(current, "runtime_id") != null) {
                return Json.text(current, "runtime_id");
            }
            ctx.app.http.clock().sleep(1);
            current = Objects.get(ctx, k, platformId);
        }
        if (Json.text(current, "runtime_id") != null) {
            return Json.text(current, "runtime_id");
        }
        throw new Fail(Exit.UNAVAILABLE, "object_projection_pending",
                k.word() + " " + platformId + " is still publishing; nothing after it was applied");
    }

    private Fail partial(Ctx ctx, int done, int total, Fail fail) {
        Fail notSaved = null;
        try {
            activate(ctx, "partial");
        } catch (Fail f) {
            notSaved = f;
        }
        report(ctx, "partial");
        if (notSaved != null) {
            fail.hint("the partial record was not saved: " + notSaved.text() + " (" + notSaved.code() + ")");
        }
        fail.hint(Assets.message("canvas_apply_partial",
                "done", Integer.toString(done), "total", Integer.toString(total), "id", planned.canvasId()));
        return fail;
    }

    private ObjectNode objectsJson() {
        ObjectNode objects = Json.object();
        recorded.forEach((h, id) -> objects.putObject(h).put("id", id));
        return objects;
    }

    /** D7 step 7: {@code HANDLE  KIND  ACTION  ID}, then every new secret, once. */
    private void report(Ctx ctx, String status) {
        if (ctx.out.json()) {
            ObjectNode value = Json.object();
            value.set("objects", objectsJson());
            ArrayNode list = value.putArray("secrets");
            secrets.forEach(list::add);
            value.put("status", status);
            ctx.out.jsonValue(value);
        } else {
            StringBuilder text = new StringBuilder(Table.render(List.of("HANDLE", "KIND", "ACTION", "ID"), rows));
            if (!secrets.isEmpty()) {
                List<List<String>> secretRows = new ArrayList<>();
                secrets.forEach(s -> secretRows.add(List.of(Json.text(s, "handle", "-"), Json.text(s, "id", "-"), Json.text(s, "value", "-"))));
                text.append('\n').append(Table.render(List.of("HANDLE", "SECRET_ID", "SECRET"), secretRows));
            }
            ctx.out.print(text.toString());
        }
        if (!secrets.isEmpty()) {
            ctx.out.note("Each secret is shown once: store them now. They cannot be read again.");
        }
    }

    /** {@code {draft_version, outcome, objects}}: runtime ids only (16.10.26x D4). */
    private void activate(Ctx ctx, String outcome) {
        ObjectNode body = Json.object();
        body.put("draft_version", planned.draftVersion());
        body.put("outcome", outcome);
        body.set("objects", objectsJson());
        try {
            Calls.call(ctx, Request.of(Fixed.CANVAS_ACTIVATE.route(), Map.of("id", planned.canvasId())).withJson(body));
        } catch (Fail f) {
            if (f.code().equals("canvas_conflict")) {
                f.hint("the canvas changed while applying; run `lingara canvas get " + planned.canvasId() + "`");
            }
            throw f;
        }
    }
}
