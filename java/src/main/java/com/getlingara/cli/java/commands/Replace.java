package com.getlingara.cli.java.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.cli.WebhookCli;
import com.getlingara.cli.java.core.Assets;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Json;
import com.getlingara.cli.java.core.Made;
import com.getlingara.cli.java.http.Routes.Op;
import java.util.List;

/**
 * {@code webhook outbound replace} (16.10.26ad D1, D5): a new endpoint with a new signing secret,
 * then the old one deleted. Both prompts, or the {@code --confirm} check, complete
 * <strong>before</strong> the new endpoint's first write, so a decline sends neither the create
 * nor the delete. If the create lands and the delete is refused, the new secret is still revealed
 * and both ids named.
 */
final class Replace {
    private Replace() {}

    /** The old object and its serving version's body: where the new endpoint's client and any omitted flag come from. */
    private record Looked(JsonNode old, JsonNode body) {}

    private static Looked lookups(Ctx ctx, String id) {
        JsonNode old = Objects.get(ctx, Kind.WEBHOOK_OUTBOUND, id);
        String semver = Objects.baseOf(old);
        JsonNode version = Objects.versionOp(ctx, Kind.WEBHOOK_OUTBOUND, Op.VERSION_GET, id, semver == null ? "" : semver);
        return new Looked(old, version.path("body"));
    }

    static void run(Ctx ctx, WebhookCli.OutReplace args) {
        Looked[] looked = new Looked[1];
        String email = Confirm.destructive(ctx, args.confirm, c -> {
            looked[0] = lookups(c, args.id);
            return Deletes.prompt(Kind.WEBHOOK_OUTBOUND, looked[0].old(), args.id, null, o -> Assets.message("consequence_replace"));
        });
        Looked found = looked[0] != null ? looked[0] : lookups(ctx, args.id);
        String url = args.url != null ? args.url : Json.text(found.body(), "url", "");
        List<String> events = args.event.isEmpty() ? Json.strings(found.body().path("event_types")) : args.event;
        JsonNode created = Outbound.create(ctx, Json.text(found.old(), "parent_id", ""), url, events);
        try {
            Objects.delete(ctx, Kind.WEBHOOK_OUTBOUND, args.id, null, email);
        } catch (Fail fail) {
            fail.made().clear();
            fail.made().add(new Made("webhook_outbound", Json.text(created, "id", "")));
            throw fail.hint("the old endpoint " + args.id + " still delivers; delete it with `lingara webhook outbound delete "
                    + args.id + "`");
        }
    }
}
