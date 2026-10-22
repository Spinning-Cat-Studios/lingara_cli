package com.getlingara.cli.java.http;

/**
 * One route: its {@code conformance/routes.toml} logical name (what {@code unexpected response
 * from {route}} names), method, path and base.
 */
public record Route(String name, String method, String path, boolean portal) {
    /**
     * {@code oauth.token} and {@code oauth.device_authorization}: no bearer, and their RFC
     * envelope is B2 rule 3's.
     */
    public boolean isToken() {
        return name.equals("oauth.token") || name.equals("oauth.device_authorization");
    }

    public boolean isPlatform() {
        return path.startsWith("/platform/");
    }
}
