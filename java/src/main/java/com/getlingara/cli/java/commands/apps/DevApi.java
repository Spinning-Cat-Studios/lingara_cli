package com.getlingara.cli.java.commands.apps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.auth.Calls;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Json;
import com.getlingara.cli.java.http.Request;
import com.getlingara.cli.java.http.Routes.Fixed;
import java.util.Map;
import java.util.function.Function;

/**
 * The five {@code app_dev.*} routes (Backend 16.10.26v §12; ADR 16.10.26ae R1–R3), one function
 * each, plus the read-then-patch every writer of the dev version uses: a {@code 409
 * version_conflict} re-reads once and retries, and a second one is the server's {@code refused}.
 */
final class DevApi {
    private DevApi() {}

    /**
     * What an open answers: the session, and the secrets it minted, which live only in this
     * process's memory and the app's environment (D5). {@code clientSecret} is {@code null} when
     * none was asked for.
     */
    record Session(String id, long leaseSecs, String expiresAt, String appSecret, String clientSecret) {}

    private static JsonNode call(Ctx ctx, Fixed route, Map<String, String> vars, JsonNode body) {
        Request request = Request.of(route.route(), vars);
        return Calls.call(ctx, body == null ? request : request.withJson(body));
    }

    /** {@code GET …/dev}: the manifest, {@code render_url}, {@code draft_version}, the session. */
    static JsonNode get(Ctx ctx, String id) {
        return call(ctx, Fixed.APP_DEV_GET, Map.of("id", id), null);
    }

    static JsonNode patch(Ctx ctx, String id, JsonNode body) {
        return call(ctx, Fixed.APP_DEV_PATCH, Map.of("id", id), body);
    }

    /** Reads {@code draft_version}, then patches {@code build(draft_version)}; one re-read and retry on {@code version_conflict}. */
    static JsonNode patchWithRetry(Ctx ctx, String id, Function<JsonNode, JsonNode> build) {
        JsonNode draft = draftVersion(get(ctx, id));
        try {
            return patch(ctx, id, build.apply(draft));
        } catch (Fail fail) {
            if (!fail.code().equals("version_conflict")) {
                throw fail;
            }
            return patch(ctx, id, build.apply(draftVersion(get(ctx, id))));
        }
    }

    private static JsonNode draftVersion(JsonNode view) {
        JsonNode v = view.get("draft_version");
        return v == null ? Json.object().nullNode() : v;
    }

    /** {@code POST …/dev/session {take_over, credentials}}. */
    static Session open(Ctx ctx, String id, boolean takeOver, boolean client) {
        ObjectNode body = Json.object();
        body.put("take_over", takeOver);
        if (client) {
            body.putArray("credentials").add("client");
        }
        JsonNode answer = call(ctx, Fixed.APP_DEV_SESSION_OPEN, Map.of("id", id), body);
        JsonNode lease = answer.path("lease_secs");
        return new Session(
                Json.text(answer, "session_id", ""),
                lease.isIntegralNumber() && lease.asLong() > 0 ? lease.asLong() : 300,
                Json.text(answer, "expires_at", ""),
                Json.text(answer.path("app_secret"), "secret", ""),
                Json.text(answer.path("client_secret"), "secret"));
    }

    static JsonNode renew(Ctx ctx, String id, String sessionId) {
        return call(ctx, Fixed.APP_DEV_SESSION_RENEW, Map.of("id", id, "session_id", sessionId), null);
    }

    /** {@code DELETE …/dev/session/{sid}}: parks, keeps {@code render_url}, revokes the session's credentials. */
    static JsonNode end(Ctx ctx, String id, String sessionId) {
        return call(ctx, Fixed.APP_DEV_SESSION_END, Map.of("id", id, "session_id", sessionId), null);
    }
}
