package com.getlingara.cli.java.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.auth.Calls;
import com.getlingara.cli.java.cli.Mixins.Deletion;
import com.getlingara.cli.java.cli.OutboundCli;
import com.getlingara.cli.java.cli.WebhookCli;
import com.getlingara.cli.java.core.Assets;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Json;
import com.getlingara.cli.java.http.Request;
import com.getlingara.cli.java.http.Routes;
import com.getlingara.cli.java.http.Routes.Op;
import java.util.List;
import java.util.Map;

/**
 * Outbound webhooks (16.10.26ad D2): a {@code webhook_out} object (body {@code {url,
 * event_types[]}}) under its client. A body update keeps the endpoint and its signing secret;
 * enabled is object-level, never version body.
 */
public final class Outbound {
    private static final String[] COLUMNS = {
        "ID", "id", "CLIENT", "parent_id", "URL", "url", "EVENTS", "event_types", "ENABLED", "enabled"
    };

    private Outbound() {}

    public static void list(Ctx ctx) {
        Lists.printList(ctx, Kind.WEBHOOK_OUTBOUND, COLUMNS);
    }

    /** Create, publish, mint the signing secret (revealed once), read. */
    public static JsonNode create(Ctx ctx, String client, String url, List<String> events) {
        ObjectNode body = Json.object();
        body.put("url", url);
        body.set("event_types", Json.array(events));
        // The verb takes no name, so the object is named by its URL, cut to the 80 characters a
        // platform object name may hold (16.10.26v).
        String name = url.codePoints().limit(80)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        Creation made = new Creation();
        return made.conclude(ctx, () -> {
            String id = Json.text(made.object(ctx, Kind.WEBHOOK_OUTBOUND, name, client, body), "id", "");
            made.secret(ctx, Kind.WEBHOOK_OUTBOUND, id);
            return new Creation.Last(Kind.WEBHOOK_OUTBOUND, id);
        });
    }

    /** {@code enable}, {@code disable} or {@code test}: object-level, never versioned. */
    private static JsonNode objectAction(Ctx ctx, Op op, String id) {
        return Calls.call(ctx, Request.of(Routes.object(Kind.WEBHOOK_OUTBOUND.word(), op), Map.of("id", id)));
    }

    public static void test(Ctx ctx, String id) {
        ctx.out.value(objectAction(ctx, Op.TEST, id));
    }

    /** {@code --url}/{@code --event} are a body update; {@code --enable}/{@code --disable} follow it. */
    public static JsonNode update(Ctx ctx, OutboundCli.Update args) {
        JsonNode last = null;
        if (args.url != null || !args.event.isEmpty()) {
            last = Objects.bodyUpdate(ctx, Kind.WEBHOOK_OUTBOUND, args.id, body -> {
                if (args.url != null) {
                    body.put("url", args.url);
                }
                if (!args.event.isEmpty()) {
                    body.set("event_types", Json.array(args.event));
                }
            });
        }
        if (args.enable || args.disable) {
            last = objectAction(ctx, args.enable ? Op.ENABLE : Op.DISABLE, args.id);
        }
        if (last == null) {
            throw Fail.usage("nothing to update: pass --url, --event, --enable or --disable");
        }
        return last;
    }

    public static void delete(Ctx ctx, Deletion args) {
        Deletes.deleteObject(ctx, Kind.WEBHOOK_OUTBOUND, args, o -> Assets.message("consequence_webhook_outbound"));
    }

    public static void replace(Ctx ctx, WebhookCli.OutReplace args) {
        Replace.run(ctx, args);
    }
}
