package com.getlingara.cli.java.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Json;
import com.getlingara.cli.java.core.Made;
import com.getlingara.cli.java.core.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A create that may take several calls (16.10.26ad D1, D2). It ends with one {@code <word>.get},
 * printed with any secret it minted. Failing part-way, it still reveals that secret on stdout and
 * names every object it made on stderr, then exits with the failing call's code. Nothing is
 * rolled back.
 */
public final class Creation {
    private final List<Made> made = new ArrayList<>();
    /** {@code {id, value}}, the D6 member a create's JSON gains. */
    private ObjectNode secret;

    /** Where a create ended: the kind and id its final read is of. */
    public record Last(Kind kind, String id) {}

    /** One object: created, named as made, then published when private. */
    public JsonNode object(Ctx ctx, Kind k, String name, String parent, JsonNode body) {
        JsonNode created = Objects.create(ctx, k, name, parent, body);
        made.add(new Made(k.word(), Json.text(created, "id", "")));
        String semver = Json.text(created.path("version"), "semver", "");
        Objects.publishIfPrivate(ctx, k, created, semver);
        return created;
    }

    /**
     * The one-time secret. {@code 409 object_projection_pending} means the client is still
     * publishing (16.10.26v §5): {@code refused}, with a retry hint.
     */
    public void secret(Ctx ctx, Kind k, String id) {
        JsonNode minted;
        try {
            minted = Objects.secretCreate(ctx, k, id);
        } catch (Fail fail) {
            if (fail.code().equals("object_projection_pending")) {
                fail.hint("the client is still publishing; run `lingara client secret create " + id + "` in a moment");
            }
            throw fail;
        }
        secret = Json.object();
        secret.set("id", minted.path("secret_id").isMissingNode() ? Json.object().nullNode() : minted.get("secret_id"));
        secret.set("value", minted.path("secret").isMissingNode() ? Json.object().nullNode() : minted.get("secret"));
    }

    /** The final read and its printing, or the failure with what was made. */
    public JsonNode conclude(Ctx ctx, Supplier<Last> steps) {
        JsonNode object;
        try {
            Last last = steps.get();
            object = Objects.get(ctx, last.kind(), last.id());
        } catch (Fail fail) {
            print(ctx, null);
            fail.made().addAll(made);
            throw fail;
        }
        print(ctx, object);
        return object;
    }

    private void print(Ctx ctx, JsonNode object) {
        if (object == null && secret == null) {
            return;
        }
        if (ctx.out.json()) {
            ObjectNode value = object != null && object.isObject() ? ((ObjectNode) object).deepCopy() : Json.object();
            if (secret != null) {
                value.set("secret", secret);
            }
            ctx.out.jsonValue(value);
        } else {
            List<List<String>> rows = object == null ? new ArrayList<>() : new ArrayList<>(Table.fields(object));
            if (secret != null) {
                rows.add(List.of("SECRET", Table.cell(secret.get("value"))));
            }
            ctx.out.print(Table.render(List.of("FIELD", "VALUE"), rows));
        }
        if (secret != null) {
            ctx.out.note("The secret is shown once: store it now. It cannot be read again.");
        }
    }
}
