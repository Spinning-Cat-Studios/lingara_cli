package com.getlingara.cli.java.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.getlingara.cli.java.core.Json;
import com.getlingara.cli.java.core.Rfc3339;
import com.getlingara.cli.java.store.ProfileRecord;

/**
 * A token response (RFC 6749 §5.1), read leniently, and the profile record it becomes
 * (16.10.26ad D4): a missing {@code expires_in} is an hour.
 */
public record Tokens(String access, String refresh, String scope, long expiresIn) {
    /** The four scopes, in one grant: the platform pair opens {@code /platform/*}, the canvas pair {@code /v1/canvases}. */
    public static final String SCOPES = "platform:read platform:write canvas:read canvas:write";

    /** A stored access token this close to expiry is refreshed first (D3). */
    public static final long REFRESH_SKEW_SECS = 60;

    public static Tokens read(JsonNode value) {
        JsonNode expires = Json.at(value, "expires_in");
        return new Tokens(
                Json.text(value, "access_token", ""),
                Json.text(value, "refresh_token"),
                Json.text(value, "scope"),
                expires.canConvertToLong() && expires.isIntegralNumber() ? expires.asLong() : 3600);
    }

    /**
     * {@code record} with these tokens: the refresh token rotates when one was sent, and {@code
     * scopes} is rewritten whenever the answer carried {@code scope}.
     */
    public void apply(ProfileRecord record, long now) {
        record.accessToken = access;
        if (refresh != null) {
            record.refreshToken = refresh;
        }
        if (scope != null) {
            record.scopes = scope;
        }
        record.accessExpiresAt = Rfc3339.format(now + expiresIn);
    }
}
