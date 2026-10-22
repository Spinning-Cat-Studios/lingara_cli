package com.getlingara.cli.java.http;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * What a request is authorised by: nothing (the token routes), a bearer token, or RFC 6749's
 * {@code client_secret_basic} for the CI mint (16.10.26ad D3). {@code header} is the {@code
 * Authorization} value, {@code null} for none.
 */
public record Auth(String header) {
    public static final Auth NONE = new Auth(null);

    public static Auth bearer(String token) {
        return new Auth("Bearer " + token);
    }

    /** Both halves form-encoded first, as RFC 6749 §2.3.1 says. */
    public static Auth basic(String id, String secret) {
        String raw = Url.encode(id) + ":" + Url.encode(secret);
        return new Auth("Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
    }
}
