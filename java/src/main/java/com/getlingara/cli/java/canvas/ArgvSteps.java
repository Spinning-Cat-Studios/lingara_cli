package com.getlingara.cli.java.canvas;

import com.fasterxml.jackson.databind.JsonNode;
import com.getlingara.cli.java.canvas.Argv.Part;
import com.getlingara.cli.java.canvas.Argv.Step;
import com.getlingara.cli.java.core.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One plan op and its verdict as one step's argv: the table of D7 step 5, kind by kind. */
final class ArgvSteps {
    private ArgvSteps() {}

    /** What every step reads besides its own op: the live lists, the kept records, the whole plan. */
    record Known(Plan.Live live, Map<String, String> kept, List<Plan.Op> ops) {}

    private static List<Part> repeated(String flag, List<String> values) {
        List<Part> out = new ArrayList<>();
        values.forEach(v -> {
            out.add(Part.text(flag));
            out.add(Part.text(v));
        });
        return out;
    }

    /** The client an op points at: made in this run, or a recorded one's platform object id. */
    private static Part clientRef(String handle, Known k) {
        boolean madeHere = k.ops().stream().anyMatch(o -> o.handle().equals(handle) && o.op().equals("create"));
        String record = k.kept().get(handle);
        String known = record == null ? null : k.live().platformId("client", record);
        return !madeHere && known != null ? Part.text(known) : Part.made(handle);
    }

    static Step step(Plan.Op op, JsonNode verdict, Known k) {
        List<String> changes = Json.strings(verdict.path("changes"));
        if (op.op().equals("update") && changes.isEmpty()) {
            return new Step(op.handle(), op.kind(), "unchanged", List.of(), "unchanged", null);
        }
        Part own = own(op, k);
        Part client = op.client() == null ? Part.made("") : clientRef(op.client(), k);
        JsonNode body = op.body() == null ? Json.object().nullNode() : op.body();
        boolean create = op.op().equals("create");
        return switch (op.kind()) {
            case "client" -> made(op, client(body, create, own), create);
            case "secret" -> secret(op, verdict, client);
            case "webhook" -> made(op, create ? webhookCreate(body, client) : webhookUpdate(body, changes, own), create);
            default -> made(op, create ? appCreate(op, body, client) : appUpdate(op, own), create);
        };
    }

    /** The op's own object: a recorded one's platform object id, else the one this run makes. */
    private static Part own(Plan.Op op, Known k) {
        String ownId = op.id() == null ? null : k.live().platformId(op.kind(), op.id());
        return ownId != null ? Part.text(ownId) : Part.made(op.handle());
    }

    /** A step whose create records the new object's runtime id, and whose update records nothing new. */
    private static Step made(Plan.Op op, List<Part> argv, boolean create) {
        return new Step(op.handle(), op.kind(), op.op(), argv, create ? "runtime_id" : "unchanged", null);
    }

    private static List<Part> appUpdate(Plan.Op op, Part own) {
        return List.of(Part.text("apps"), Part.text("update"), own, Part.text("--manifest"), new Part(Part.MANIFEST, op.handle()));
    }

    private static Step secret(Plan.Op op, JsonNode verdict, Part client) {
        if ("minted_with_client".equals(Json.text(verdict, "note"))) {
            return new Step(op.handle(), op.kind(), op.op(), List.of(), "client_secret", op.client() == null ? "" : op.client());
        }
        List<Part> argv = List.of(Part.text("oauth_client"), Part.text("secret"), Part.text("create"), client);
        return new Step(op.handle(), op.kind(), op.op(), argv, "secret_id", null);
    }

    private static List<Part> client(JsonNode body, boolean create, Part own) {
        List<Part> argv = new ArrayList<>(List.of(Part.text("oauth_client"), Part.text(create ? "create" : "update")));
        if (!create) {
            argv.add(own);
        }
        argv.add(Part.text("--name"));
        argv.add(Part.text(Json.text(body, "name", "")));
        argv.addAll(repeated("--scope", Json.strings(body.path("scopes"))));
        argv.addAll(repeated("--redirect-uri", Json.strings(body.path("redirect_uris"))));
        return argv;
    }

    private static List<Part> webhookCreate(JsonNode body, Part client) {
        List<Part> argv = new ArrayList<>(List.of(
                Part.text("webhook"), Part.text("outbound"), Part.text("create"), Part.text("--client"), client,
                Part.text("--url"), Part.text(Json.text(body, "url", ""))));
        argv.addAll(repeated("--event", Json.strings(body.path("event_types"))));
        return argv;
    }

    private static List<Part> webhookUpdate(JsonNode body, List<String> changes, Part own) {
        List<Part> argv = new ArrayList<>(List.of(Part.text("webhook"), Part.text("outbound"), Part.text("update"), own));
        if (changes.contains("url")) {
            argv.add(Part.text("--url"));
            argv.add(Part.text(Json.text(body, "url", "")));
        }
        if (changes.contains("event_types")) {
            argv.addAll(repeated("--event", Json.strings(body.path("event_types"))));
        }
        if (changes.contains("enabled")) {
            argv.add(Part.text("--enable"));
        }
        return argv;
    }

    private static List<Part> appCreate(Plan.Op op, JsonNode body, Part client) {
        String name = Spec.appName(body, op.handle());
        return List.of(Part.text("apps"), Part.text("create"), Part.text(name), Part.text("--client"), client,
                Part.text("--manifest"), new Part(Part.MANIFEST, op.handle()));
    }
}
