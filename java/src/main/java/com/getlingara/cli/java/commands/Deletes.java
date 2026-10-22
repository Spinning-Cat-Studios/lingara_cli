package com.getlingara.cli.java.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.cli.Mixins.Deletion;
import com.getlingara.cli.java.core.Assets;
import com.getlingara.cli.java.core.Json;
import java.util.function.Function;

/**
 * Every object {@code delete} (16.10.26ad D5): the ritual over one {@code <word>.get} for the
 * prompt, then the destructive request with the canonical email.
 */
public final class Deletes {
    private Deletes() {}

    /**
     * {@code {client}} is the read's {@code parent_id}, the client's platform object id: the one
     * {@code lingara client delete <id>} takes, never the public {@code client_id}.
     */
    public static String parent(JsonNode object) {
        return Json.text(object, "parent_id", "-");
    }

    public static Confirm.Prompt prompt(Kind k, JsonNode object, String id, String version, Function<JsonNode, String> consequence) {
        String said = version == null ? consequence.apply(object) : Assets.message("consequence_version", "semver", version);
        JsonNode pinned = object.path("pinned_by");
        return new Confirm.Prompt(k.label(), Json.text(object, "name", id), id, said, pinned.isIntegralNumber() ? pinned.asLong() : 0);
    }

    public static void deleteObject(Ctx ctx, Kind k, Deletion args, Function<JsonNode, String> consequence) {
        String email = Confirm.destructive(ctx, args.confirm,
                c -> prompt(k, Objects.get(c, k, args.id), args.id, args.version, consequence));
        Objects.delete(ctx, k, args.id, args.version, email);
        ctx.out.nothing();
    }
}
