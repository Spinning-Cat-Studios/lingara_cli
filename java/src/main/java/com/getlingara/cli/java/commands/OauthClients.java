package com.getlingara.cli.java.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.auth.Calls;
import com.getlingara.cli.java.auth.Tokens;
import com.getlingara.cli.java.cli.ClientCli;
import com.getlingara.cli.java.cli.Choices.ClientType;
import com.getlingara.cli.java.cli.Mixins.Deletion;
import com.getlingara.cli.java.core.Assets;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Json;
import com.getlingara.cli.java.http.Request;
import com.getlingara.cli.java.http.Routes;
import com.getlingara.cli.java.http.Routes.Op;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * {@code lingara oauth_client …} (16.10.26ad D1, D2). Rotation is two commands, because it is
 * two calls: {@code secret create} while the old secret stays live, then {@code secret delete}
 * once the new one is deployed.
 */
public final class OauthClients {
    private static final String[] COLUMNS = {
        "ID", "id", "NAME", "name", "TYPE", "client_type", "SCOPES", "allowed_scopes", "CREATED", "created_at"
    };
    private static final String SHOWN_ONCE = "The secret is shown once: store it now. It cannot be read again.";

    private OauthClients() {}

    public static void list(Ctx ctx) {
        Lists.printList(ctx, Kind.OAUTH_CLIENT, COLUMNS);
    }

    /** The client's version body (16.10.26v §1). {@code --ci} is confidential with the four scopes. */
    public static ObjectNode body(ClientCli.Create args) {
        List<String> scopes = args.ci ? Arrays.asList(Tokens.SCOPES.split(" ")) : args.scope;
        ObjectNode body = Json.object();
        body.put("client_type", args.ci ? "confidential" : args.type.wire());
        body.set("allowed_scopes", Json.array(scopes));
        body.put("billing_mode", args.billingMode.wire());
        body.set("redirect_uris", Json.array(args.redirectUri));
        return body;
    }

    /** Create, publish, mint the confidential client's one-time secret, read. */
    public static JsonNode create(Ctx ctx, ClientCli.Create args) {
        Creation made = new Creation();
        boolean confidential = args.ci || args.type == ClientType.CONFIDENTIAL;
        return made.conclude(ctx, () -> {
            String id = Json.text(made.object(ctx, Kind.OAUTH_CLIENT, args.name, null, body(args)), "id", "");
            if (confidential) {
                made.secret(ctx, Kind.OAUTH_CLIENT, id);
            }
            return new Creation.Last(Kind.OAUTH_CLIENT, id);
        });
    }

    /** {@code --name} is a rename; any other flag is a body update. */
    public static void update(Ctx ctx, ClientCli.Update args) {
        JsonNode last = null;
        if (args.name != null) {
            last = Objects.rename(ctx, Kind.OAUTH_CLIENT, args.id, args.name);
        }
        if (!args.scope.isEmpty() || args.apiVersion != null || !args.redirectUri.isEmpty()) {
            last = Objects.bodyUpdate(ctx, Kind.OAUTH_CLIENT, args.id, body -> {
                if (!args.scope.isEmpty()) {
                    body.set("allowed_scopes", Json.array(args.scope));
                }
                if (args.apiVersion != null) {
                    body.put("api_version", args.apiVersion);
                }
                if (!args.redirectUri.isEmpty()) {
                    body.set("redirect_uris", Json.array(args.redirectUri));
                }
            });
        }
        if (last == null) {
            throw Fail.usage("nothing to update: pass --name, --scope, --api-version or --redirect-uri");
        }
        ctx.out.value(last);
    }

    public static void delete(Ctx ctx, Deletion args) {
        Deletes.deleteObject(ctx, Kind.OAUTH_CLIENT, args, OauthClients::consequence);
    }

    /** The children 16.10.26v §7's cascade deletes, counted by kind. */
    static String consequence(JsonNode object) {
        int[] counts = new int[3];
        for (JsonNode child : object.path("children")) {
            String kind = Json.text(child, "kind", "");
            int slot = switch (kind) {
                case "app" -> 0;
                case "embed" -> 1;
                case "webhook_in", "webhook_out" -> 2;
                default -> -1;
            };
            if (slot >= 0) {
                counts[slot]++;
            }
        }
        return Assets.message("consequence_oauth_client",
                "apps", Integer.toString(counts[0]), "embeds", Integer.toString(counts[1]), "webhooks", Integer.toString(counts[2]));
    }

    public static void secretCreate(Ctx ctx, String clientId) {
        JsonNode minted = Objects.secretCreate(ctx, Kind.OAUTH_CLIENT, clientId);
        ctx.out.value(minted);
        ctx.out.note(SHOWN_ONCE);
    }

    /** A secret has no read of its own, so the prompt names the client's. */
    public static void secretDelete(Ctx ctx, ClientCli.SecretDelete args) {
        String email = Confirm.destructive(ctx, args.confirm, c -> {
            JsonNode client = Objects.get(c, Kind.OAUTH_CLIENT, args.clientId);
            Confirm.Prompt p = Deletes.prompt(Kind.OAUTH_CLIENT, client, args.secretId, null, o -> Assets.message("consequence_secret"));
            return new Confirm.Prompt("secret", p.name(), p.id(), p.consequence(), 0);
        });
        ObjectNode confirm = Json.object();
        confirm.put("confirm", email);
        Map<String, String> vars = Map.of("id", args.clientId, "secret_id", args.secretId);
        Calls.call(ctx, Request.of(Routes.object(Kind.OAUTH_CLIENT.word(), Op.SECRET_DELETE), vars).withJson(confirm));
        ctx.out.nothing();
    }
}
