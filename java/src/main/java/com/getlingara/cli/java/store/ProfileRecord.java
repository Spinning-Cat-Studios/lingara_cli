package com.getlingara.cli.java.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.cli.java.core.Json;
import java.util.Objects;

/** One profile's credentials: 16.10.26ad D4's record, stored whole, in this member order. */
public final class ProfileRecord {
    public String developerId = "";
    public String email = "";
    /** The token response's space-separated {@code scope}, rewritten at each refresh. */
    public String scopes = "";
    public String refreshToken = "";
    public String accessToken = "";
    /** RFC 3339, UTC. */
    public String accessExpiresAt = "";

    public ProfileRecord copy() {
        return read(toJson());
    }

    public ObjectNode toJson() {
        ObjectNode out = Json.object();
        out.put("developer_id", developerId);
        out.put("email", email);
        out.put("scopes", scopes);
        out.put("refresh_token", refreshToken);
        out.put("access_token", accessToken);
        out.put("access_expires_at", accessExpiresAt);
        return out;
    }

    /** A stored record; a missing member reads as empty, as the reference kit's default does. */
    public static ProfileRecord read(JsonNode node) {
        ProfileRecord r = new ProfileRecord();
        r.developerId = Json.text(node, "developer_id", "");
        r.email = Json.text(node, "email", "");
        r.scopes = Json.text(node, "scopes", "");
        r.refreshToken = Json.text(node, "refresh_token", "");
        r.accessToken = Json.text(node, "access_token", "");
        r.accessExpiresAt = Json.text(node, "access_expires_at", "");
        return r;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ProfileRecord r && toJson().equals(r.toJson());
    }

    @Override
    public int hashCode() {
        return Objects.hash(developerId, email, scopes, refreshToken, accessToken, accessExpiresAt);
    }
}
