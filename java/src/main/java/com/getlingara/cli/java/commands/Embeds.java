package com.getlingara.cli.java.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.cli.EmbedCli;
import com.getlingara.cli.java.cli.Mixins.Deletion;
import com.getlingara.cli.java.core.Assets;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Json;

/**
 * {@code lingara embed …} (16.10.26ad D2): an {@code embed} object (body {@code {origins[]}}) on a
 * metered, confidential client, which holds {@code embed:mint} through the embed's projection,
 * never through its own body (16.10.26v §1).
 */
public final class Embeds {
    private static final String[] COLUMNS = {
        "ID", "id", "NAME", "name", "CLIENT", "parent_id", "ORIGINS", "origins", "SERVING", "serving_version"
    };

    private Embeds() {}

    public static void list(Ctx ctx) {
        Lists.printList(ctx, Kind.EMBED, COLUMNS);
    }

    /** The client first (confidential, metered), its secret, then the embed. */
    public static void create(Ctx ctx, EmbedCli.Create args) {
        ObjectNode clientBody = Json.object();
        clientBody.put("client_type", "confidential");
        clientBody.put("billing_mode", "metered");
        clientBody.set("allowed_scopes", Json.array(args.scope));
        Creation made = new Creation();
        made.conclude(ctx, () -> {
            String clientId = Json.text(made.object(ctx, Kind.OAUTH_CLIENT, args.name, null, clientBody), "id", "");
            made.secret(ctx, Kind.OAUTH_CLIENT, clientId);
            ObjectNode body = Json.object();
            body.set("origins", Json.array(args.origin));
            JsonNode embed = made.object(ctx, Kind.EMBED, args.name, clientId, body);
            return new Creation.Last(Kind.EMBED, Json.text(embed, "id", ""));
        });
    }

    /** {@code --name} renames; {@code --origin} replaces the origins in a body update. */
    public static void update(Ctx ctx, EmbedCli.Update args) {
        JsonNode last = null;
        if (args.name != null) {
            last = Objects.rename(ctx, Kind.EMBED, args.id, args.name);
        }
        if (!args.origin.isEmpty()) {
            last = Objects.bodyUpdate(ctx, Kind.EMBED, args.id, body -> body.set("origins", Json.array(args.origin)));
        }
        if (last == null) {
            throw Fail.usage("nothing to update: pass --name or --origin");
        }
        ctx.out.value(last);
    }

    public static void delete(Ctx ctx, Deletion args) {
        Deletes.deleteObject(ctx, Kind.EMBED, args, o -> Assets.message("consequence_embed", "client", Deletes.parent(o)));
    }
}
