package com.getlingara.cli.java.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.getlingara.cli.java.core.Json;

/**
 * A non-2xx answer, with what the classifier's rules read about the request behind it.
 *
 * @param route the logical route name
 * @param token {@code oauth.token} or {@code oauth.device_authorization}
 * @param platform under {@code /platform/}, where the dark 404 is spoken
 * @param bearer the request carried {@code Authorization: Bearer} (rule 2)
 */
public record Answer(String route, boolean token, boolean platform, boolean bearer, Wire.In reply) {
    public static Answer of(Route route, Wire.In reply, boolean bearer) {
        return new Answer(route.name(), route.isToken(), route.isPlatform(), bearer, reply);
    }

    public int status() {
        return reply.status();
    }

    public JsonNode json() {
        return Json.parse(reply.body());
    }

    /** A string member of the JSON body, or {@code null}. */
    public String member(String key) {
        JsonNode body = json();
        return body != null && body.isObject() ? Json.text(body, key) : null;
    }

    /** {@code {code, error}}, the platform's and {@code /v1}'s envelope. */
    public boolean coded() {
        return member("code") != null && member("error") != null;
    }

    /** RFC 6749 / 8628: an {@code error} member and no {@code code}. */
    public boolean oauthEnvelope() {
        return member("error") != null && member("code") == null;
    }

    public boolean emptyBody() {
        for (byte b : reply.body()) {
            if (!Character.isWhitespace(b)) {
                return false;
            }
        }
        return true;
    }

    /** {@code Retry-After} in seconds, if the answer carried one. */
    public Long retryAfter() {
        String value = reply.header("retry-after");
        try {
            return value == null ? null : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
