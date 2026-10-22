package com.getlingara.cli.java.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.auth.Calls;
import com.getlingara.cli.java.core.Json;
import com.getlingara.cli.java.core.Pair;
import com.getlingara.cli.java.http.Request;
import com.getlingara.cli.java.http.Route;
import com.getlingara.cli.java.http.Routes;
import com.getlingara.cli.java.http.Routes.Op;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The {@code /platform/objects} family every kind shares (16.10.26v §8; 16.10.26ad D2): list every
 * page, read, rename, create and publish, the body update that respects the one editable version,
 * delete with the typed email, and the one-time secret mint.
 */
public final class Objects {
    private Objects() {}

    private static JsonNode send(Ctx ctx, Route route, Map<String, String> vars, JsonNode body) {
        Request request = Request.of(route, vars);
        return Calls.call(ctx, body == null ? request : request.withJson(body));
    }

    public static List<JsonNode> list(Ctx ctx, Kind k) {
        return Lists.pages(ctx, Request.of(Routes.object(k.word(), Op.LIST)), List.of(new Pair("kind", k.kind())));
    }

    public static JsonNode get(Ctx ctx, Kind k, String id) {
        return send(ctx, Routes.object(k.word(), Op.GET), Map.of("id", id), null);
    }

    public static JsonNode rename(Ctx ctx, Kind k, String id, String name) {
        ObjectNode body = Json.object();
        body.put("name", name);
        return send(ctx, Routes.object(k.word(), Op.RENAME), Map.of("id", id), body);
    }

    /** The object and its first draft: {@code {kind, name, parent_id?, body}}. */
    public static JsonNode create(Ctx ctx, Kind k, String name, String parent, JsonNode body) {
        ObjectNode request = Json.object();
        request.put("kind", k.kind());
        request.put("name", name);
        request.set("body", body);
        if (parent != null) {
            request.put("parent_id", parent);
        }
        return send(ctx, Routes.object(k.word(), Op.CREATE), Map.of(), request);
    }

    public static JsonNode versionOp(Ctx ctx, Kind k, Op op, String id, String semver) {
        return send(ctx, Routes.object(k.word(), op), Map.of("id", id, "semver", semver), null);
    }

    /**
     * A private object skips review, so a version is published at once (16.10.26v §4). A public
     * one stays a draft for {@code apps submit}. {@code null} when nothing was published.
     */
    public static JsonNode publishIfPrivate(Ctx ctx, Kind k, JsonNode object, String semver) {
        if ("public".equals(Json.text(object, "visibility")) || semver.isEmpty()) {
            return null;
        }
        return versionOp(ctx, k, Op.PUBLISH, Json.text(object, "id", ""), semver);
    }

    /**
     * D2's body update: overlay the flags on the one editable version ({@code version_put} at its
     * {@code draft_version}), else on the serving (or highest) version as a new one ({@code
     * version_create}). Always a whole body.
     */
    public static JsonNode bodyUpdate(Ctx ctx, Kind k, String id, Consumer<ObjectNode> overlay) {
        JsonNode current = get(ctx, k, id);
        JsonNode versions = current.path("versions");
        String editable = null;
        for (JsonNode v : versions) {
            String state = Json.text(v, "state");
            if (editable == null && ("draft".equals(state) || "changes_requested".equals(state))) {
                editable = Json.text(v, "semver");
            }
        }
        JsonNode answer = editable != null
                ? putEditable(ctx, k, id, editable, overlay)
                : createVersion(ctx, k, new String[] {id, baseOf(current)}, overlay);
        JsonNode published = publishIfPrivate(ctx, k, current, Json.text(answer, "semver", ""));
        return published != null ? published : answer;
    }

    private static JsonNode putEditable(Ctx ctx, Kind k, String id, String semver, Consumer<ObjectNode> overlay) {
        JsonNode version = versionOp(ctx, k, Op.VERSION_GET, id, semver);
        ObjectNode edit = Json.object();
        edit.set("body", overlaid(version.path("body"), overlay));
        JsonNode draftVersion = version.get("draft_version");
        edit.set("draft_version", draftVersion == null ? NullNode.getInstance() : draftVersion);
        return send(ctx, Routes.object(k.word(), Op.VERSION_PUT), Map.of("id", id, "semver", semver), edit);
    }

    /** The serving version, else the first listed; {@code null} for none. */
    public static String baseOf(JsonNode object) {
        String serving = Json.text(object, "serving_version");
        JsonNode versions = object.path("versions");
        if (serving == null && versions.size() > 0) {
            return Json.text(versions.get(0), "semver");
        }
        return serving;
    }

    /** {@code idAndBase} is the object's id and the version the new one starts from. */
    private static JsonNode createVersion(Ctx ctx, Kind k, String[] idAndBase, Consumer<ObjectNode> overlay) {
        String id = idAndBase[0];
        JsonNode start = idAndBase[1] == null ? Json.object() : versionOp(ctx, k, Op.VERSION_GET, id, idAndBase[1]).path("body");
        ObjectNode made = Json.object();
        made.set("body", overlaid(start, overlay));
        return send(ctx, Routes.object(k.word(), Op.VERSION_CREATE), Map.of("id", id), made);
    }

    private static ObjectNode overlaid(JsonNode body, Consumer<ObjectNode> overlay) {
        ObjectNode out = body.isObject() ? ((ObjectNode) body).deepCopy() : Json.object();
        overlay.accept(out);
        return out;
    }

    /** The destructive request: the canonical email in its body (B3.6). */
    public static JsonNode delete(Ctx ctx, Kind k, String id, String version, String email) {
        ObjectNode confirm = Json.object();
        confirm.put("confirm", email);
        if (version != null) {
            return send(ctx, Routes.object(k.word(), Op.DELETE_VERSION), Map.of("id", id, "semver", version), confirm);
        }
        return send(ctx, Routes.object(k.word(), Op.DELETE), Map.of("id", id), confirm);
    }

    /** {@code {secret_id, prefix, secret, expires_at}}, revealed once. */
    public static JsonNode secretCreate(Ctx ctx, Kind k, String id) {
        return send(ctx, Routes.object(k.word(), Op.SECRET_CREATE), Map.of("id", id), null);
    }

    /** A {@code get} printed: the server's JSON, or B1's {@code FIELD  VALUE} table. */
    public static void printGet(Ctx ctx, Kind k, String id) {
        ctx.out.value(get(ctx, k, id));
    }
}
