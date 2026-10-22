package com.getlingara.cli.java.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.auth.Calls;
import com.getlingara.cli.java.cli.Mixins.Deletion;
import com.getlingara.cli.java.core.Assets;
import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Json;
import com.getlingara.cli.java.core.Pair;
import com.getlingara.cli.java.http.Request;
import com.getlingara.cli.java.http.Routes;
import com.getlingara.cli.java.http.Routes.Fixed;
import com.getlingara.cli.java.http.Routes.Op;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * {@code lingara canvas …} (16.10.26ad D1, D2, D7). Content goes through {@code /v1/canvases};
 * lifecycle (delete with the typed email) through {@code /platform/objects}, after {@code
 * canvas.lookup} turns the builder's id into the platform object's. {@code apply} is {@code
 * canvas.Apply}'s.
 */
public final class Canvases {
    private static final String[] COLUMNS = {
        "ID", "id", "NAME", "name", "DRAFT", "has_draft", "ACTIVE", "active_revision", "UPDATED", "updated_at"
    };

    private Canvases() {}

    /** A {@code /v1/canvases} 404 while the builder flag is off (16.10.26x D3): where no id could be wrong, say so. */
    private static Fail builderHint(Fail fail) {
        return fail.exit() == Exit.NOT_FOUND ? fail.hint("the canvas builder may not be enabled for this account") : fail;
    }

    public static JsonNode get(Ctx ctx, String id) {
        return Calls.call(ctx, Request.of(Fixed.CANVAS_GET.route(), Map.of("id", id)));
    }

    public static JsonNode definition(Path path) {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw Fail.usage(path + ": " + e.getMessage());
        }
        JsonNode def = Json.parse(text);
        if (def == null) {
            throw Fail.usage(path + " is not JSON: " + Json.parseError(text));
        }
        return def;
    }

    /** {@code createCanvas} answers the canvas itself, so there is no final read. */
    public static void create(Ctx ctx, String name, Path file) {
        try {
            JsonNode def = file == null ? Json.parse("{\"schema\":2,\"nodes\":[],\"edges\":[]}") : definition(file);
            ObjectNode body = Json.object();
            body.set("definition", def);
            if (name != null) {
                body.put("name", name);
            }
            ctx.out.value(Calls.call(ctx, Request.of(Fixed.CANVAS_CREATE.route()).withJson(body)));
        } catch (Fail fail) {
            throw builderHint(fail);
        }
    }

    public static void list(Ctx ctx) {
        try {
            List<JsonNode> items = Lists.pages(ctx, Request.of(Fixed.CANVAS_LIST.route()), List.of());
            Lists.printRows(ctx, items, COLUMNS);
        } catch (Fail fail) {
            throw builderHint(fail);
        }
    }

    public static void rename(Ctx ctx, String id, String name) {
        ObjectNode body = Json.object();
        body.put("name", name);
        ctx.out.value(Calls.call(ctx, Request.of(Fixed.CANVAS_RENAME.route(), Map.of("id", id)).withJson(body)));
    }

    /** Saves the definition at the draft's {@code draft_version} ({@code 0}: a new draft); creates nothing else (B7). */
    public static void draft(Ctx ctx, String id, Path file) {
        JsonNode def = definition(file);
        JsonNode version = get(ctx, id).path("draft").path("draft_version");
        ObjectNode body = Json.object();
        body.set("definition", def);
        body.put("draft_version", version.isIntegralNumber() ? version.asLong() : 0);
        ctx.out.value(Calls.call(ctx, Request.of(Fixed.CANVAS_PUT_DRAFT.route(), Map.of("id", id)).withJson(body)));
    }

    /** The {@code canvas} object behind a {@code /v1/canvases} id (16.10.26v §8). */
    private static String lookup(Ctx ctx, String canvasId) {
        List<Pair> query = List.of(new Pair("kind", "canvas"), new Pair("runtime_id", canvasId));
        JsonNode page = Calls.call(ctx, Request.of(Fixed.CANVAS_LOOKUP.route()).withQuery(query));
        String found = Json.text(page.path("items").path(0), "id");
        if (found == null) {
            throw new Fail(Exit.NOT_FOUND, "object_not_found", "no canvas " + canvasId);
        }
        return found;
    }

    public static void delete(Ctx ctx, Deletion args) {
        String[] found = new String[1];
        String email = Confirm.destructive(ctx, args.confirm, c -> {
            found[0] = lookup(c, args.id);
            JsonNode object = Calls.call(c, Request.of(Routes.objectNamed("canvas.object_get", Op.GET), Map.of("id", found[0])));
            return Deletes.prompt(Kind.CANVAS, object, args.id, args.version, o -> Assets.message("consequence_canvas"));
        });
        String platformId = found[0] != null ? found[0] : lookup(ctx, args.id);
        Objects.delete(ctx, Kind.CANVAS, platformId, args.version, email);
        ctx.out.nothing();
    }
}
