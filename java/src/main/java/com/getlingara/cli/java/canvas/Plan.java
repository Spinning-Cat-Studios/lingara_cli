package com.getlingara.cli.java.canvas;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.cli.java.commands.apps.ManifestRules;
import com.getlingara.cli.java.core.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 16.10.26ad D7 step 2: the web planner's rules ({@code plan.ts}) over field nodes.
 *
 * <p>Ops come out in topological order and every client reference is a backward {@code
 * {handle}}. "Still live" means live <strong>and</strong> still on the recorded client: a
 * recorded client is live when the client list has a row with its {@code runtime_id}; a webhook
 * or app when its list has its {@code runtime_id} under that client's platform object; a secret
 * when its client is (the platform lists carry no secrets, and a recorded secret is never
 * re-planned). A node with {@code custom: true} runs a script, which only the browser's sandbox
 * may do, so it refuses the plan by its handle.
 */
public final class Plan {
    private Plan() {}

    /** One {@code /platform/objects} list row, as the planner joins it. */
    public record Row(String id, String runtimeId, String parentId) {
        public static Row read(JsonNode v) {
            return new Row(Json.text(v, "id", ""), Json.text(v, "runtime_id", ""), Json.text(v, "parent_id", ""));
        }
    }

    /** The live {@code oauth_client}, {@code webhook_out} and {@code app} lists (D7 step 1). */
    public record Live(List<Row> clients, List<Row> webhooks, List<Row> apps) {
        public Row clientByRuntime(String runtimeId) {
            return clients.stream().filter(r -> r.runtimeId().equals(runtimeId)).findFirst().orElse(null);
        }

        /** The platform object id behind a recorded runtime id of {@code kind}. */
        public String platformId(String kind, String runtimeId) {
            List<Row> rows = switch (kind) {
                case "client" -> clients;
                case "webhook" -> webhooks;
                default -> apps;
            };
            return rows.stream().filter(r -> r.runtimeId().equals(runtimeId)).map(Row::id).findFirst().orElse(null);
        }
    }

    /** {@code {handle, kind, op, client?: {handle}, id?, body?}}, the dry-run's shape. */
    public record Op(String handle, String kind, String op, String client, String id, JsonNode body) {
        public ObjectNode wire() {
            ObjectNode v = Json.object();
            v.put("handle", handle);
            v.put("kind", kind);
            v.put("op", op);
            if (client != null) {
                v.putObject("client").put("handle", client);
            }
            if (id != null) {
                v.put("id", id);
            }
            if (body != null) {
                v.set("body", body);
            }
            return v;
        }
    }

    /** Why the plan stops: {@code script}, {@code fields}, {@code no_client}, {@code cycle} or {@code ephemeral}. */
    public static final class Refusal extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public final String why;
        public final String node;
        public final String detail;

        Refusal(String why, String node, String detail) {
            super(why, null, false, false);
            this.why = why;
            this.node = node;
            this.detail = detail;
        }
    }

    /** The recorded entries still live by the rule above, keyed by handle in sorted order. */
    public static Map<String, String> stillLive(Definition def, Map<String, String> recorded, Live live) {
        Map<String, String> kept = new TreeMap<>();
        List<Definition.Node> order = def.topoOrder();
        for (Definition.Node node : order == null ? List.<Definition.Node>of() : order) {
            String record = recorded.get(node.id());
            if (record == null) {
                continue;
            }
            boolean keep;
            if (node.kind().equals("client")) {
                keep = live.clientByRuntime(record) != null;
            } else {
                String clientHandle = def.clientOf(node.id());
                String clientRecord = clientHandle == null ? null : kept.get(clientHandle);
                Row client = clientRecord == null ? null : live.clientByRuntime(clientRecord);
                keep = client != null && dependantLive(node, record, client.id(), live);
            }
            if (keep) {
                kept.put(node.id(), record);
            }
        }
        return kept;
    }

    private static boolean dependantLive(Definition.Node node, String record, String clientPlatformId, Live live) {
        List<Row> rows = switch (node.kind()) {
            case "secret" -> null;
            case "webhook" -> live.webhooks();
            default -> live.apps();
        };
        return rows == null || rows.stream().anyMatch(r -> r.runtimeId().equals(record) && r.parentId().equals(clientPlatformId));
    }

    private static JsonNode bodyFor(Definition.Node node, boolean create) {
        JsonNode body;
        try {
            body = switch (node.kind()) {
                case "client" -> Spec.client(node.fields(), create);
                case "webhook" -> Spec.webhook(node.fields());
                case "app" -> Spec.app(node.fields());
                default -> null;
            };
        } catch (Spec.Invalid e) {
            throw new Refusal("fields", node.id(), e.getMessage());
        }
        String renderUrl = body == null ? "" : Json.text(body.path("manifest"), "render_url", "");
        if (node.kind().equals("app") && ManifestRules.ephemeral(renderUrl)) {
            throw new Refusal("ephemeral", node.id(), null);
        }
        return body;
    }

    /** One node's op over its kept record; {@code null} for a recorded secret, which is never re-planned. */
    private static Op opFor(Definition def, Definition.Node node, String record) {
        if (record != null && node.kind().equals("secret")) {
            return null;
        }
        boolean isClient = node.kind().equals("client");
        String client = isClient ? null : def.clientOf(node.id());
        if (!isClient && client == null) {
            throw new Refusal("no_client", node.id(), null);
        }
        boolean create = record == null;
        return new Op(node.id(), node.kind(), create ? "create" : "update", create ? client : null, record, bodyFor(node, create));
    }

    /** D7 step 2: every field node's op, in plan order, over the kept records. */
    public static List<Op> build(Definition def, Map<String, String> kept) {
        List<Definition.Node> order = def.topoOrder();
        if (order == null) {
            throw new Refusal("cycle", null, null);
        }
        for (Definition.Node node : order) {
            if (node.custom()) {
                throw new Refusal("script", node.id(), null);
            }
        }
        List<Op> ops = new ArrayList<>();
        for (Definition.Node node : order) {
            Op op = opFor(def, node, kept.get(node.id()));
            if (op != null) {
                ops.add(op);
            }
        }
        return ops;
    }
}
