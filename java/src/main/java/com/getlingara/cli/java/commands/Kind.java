package com.getlingara.cli.java.commands;

/**
 * A CLI word and the 16.10.26v kind behind it: {@code word} is {@code routes.toml}'s ({@code
 * webhook_outbound}), {@code kind} the platform's ({@code webhook_out}), {@code label} the
 * {@code {kind}} in {@code confirm_yes_no}.
 */
public record Kind(String word, String kind, String label) {
    public static final Kind OAUTH_CLIENT = new Kind("oauth_client", "oauth_client", "OAuth client");
    public static final Kind APP = new Kind("app", "app", "app");
    public static final Kind EMBED = new Kind("embed", "embed", "embed");
    public static final Kind WEBHOOK_INBOUND = new Kind("webhook_inbound", "webhook_in", "inbound webhook");
    public static final Kind WEBHOOK_OUTBOUND = new Kind("webhook_outbound", "webhook_out", "outbound webhook");
    public static final Kind CANVAS = new Kind("canvas", "canvas", "canvas");
}
