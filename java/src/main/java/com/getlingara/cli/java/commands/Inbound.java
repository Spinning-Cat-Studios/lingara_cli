package com.getlingara.cli.java.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.cli.Mixins.Deletion;
import com.getlingara.cli.java.core.Assets;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Json;
import java.util.List;

/**
 * Inbound webhooks (16.10.26ad D2): a {@code webhook_in} object (body {@code {event_types[]}}) on
 * a confidential client, the credential a sender uses for {@code POST /v1/events}. {@code
 * events:write} comes from the child's projection, so the client's own body never names it, and
 * deleting the webhook clears it.
 */
public final class Inbound {
    private static final String[] COLUMNS = {"ID", "id", "NAME", "name", "CLIENT", "parent_id", "EVENTS", "event_types"};

    private Inbound() {}

    public static void list(Ctx ctx) {
        Lists.printList(ctx, Kind.WEBHOOK_INBOUND, COLUMNS);
    }

    /** No {@code --event} omits {@code event_types}, which 16.10.26v §5 projects as every inbound type. */
    private static ObjectNode body(List<String> events) {
        ObjectNode body = Json.object();
        if (!events.isEmpty()) {
            body.set("event_types", Json.array(events));
        }
        return body;
    }

    public static void create(Ctx ctx, String name, List<String> events) {
        ObjectNode clientBody = Json.object();
        clientBody.put("client_type", "confidential");
        clientBody.putArray("allowed_scopes");
        Creation made = new Creation();
        JsonNode hook = made.conclude(ctx, () -> {
            String clientId = Json.text(made.object(ctx, Kind.OAUTH_CLIENT, name, null, clientBody), "id", "");
            made.secret(ctx, Kind.OAUTH_CLIENT, clientId);
            JsonNode created = made.object(ctx, Kind.WEBHOOK_INBOUND, name, clientId, body(events));
            return new Creation.Last(Kind.WEBHOOK_INBOUND, Json.text(created, "id", ""));
        });
        String ingest = ctx.app.http.api().replaceAll("/+$", "") + "/v1/events";
        String clientId = Json.text(hook, "client_id", "-");
        ctx.out.note("Senders POST to " + ingest + " as client " + clientId + ", with its secret and an Idempotency-Key header.");
    }

    public static void update(Ctx ctx, String id, String name, List<String> events) {
        JsonNode last = null;
        if (name != null) {
            last = Objects.rename(ctx, Kind.WEBHOOK_INBOUND, id, name);
        }
        if (!events.isEmpty()) {
            last = Objects.bodyUpdate(ctx, Kind.WEBHOOK_INBOUND, id, b -> b.set("event_types", Json.array(events)));
        }
        if (last == null) {
            throw Fail.usage("nothing to update: pass --name or --event");
        }
        ctx.out.value(last);
    }

    public static void delete(Ctx ctx, Deletion args) {
        Deletes.deleteObject(ctx, Kind.WEBHOOK_INBOUND, args,
                o -> Assets.message("consequence_webhook_inbound", "client", Deletes.parent(o)));
    }
}
