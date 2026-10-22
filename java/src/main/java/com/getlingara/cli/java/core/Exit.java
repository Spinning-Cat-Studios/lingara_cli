package com.getlingara.cli.java.core;

/**
 * The exit codes (16.10.26ab D3, rows 0–8 and 130; 16.10.26ae D10, rows 9–18): a row of {@code
 * spec/cli.schema.json}'s exit table, its number and the name {@code apps dev --json} reports.
 */
public enum Exit {
    OK(0, "ok"),
    FAILURE(1, "failure"),
    USAGE(2, "usage"),
    AUTH_REQUIRED(3, "auth_required"),
    NOT_FOUND(4, "not_found"),
    REFUSED(5, "refused"),
    NOT_CONFIRMED(6, "not_confirmed"),
    UNAVAILABLE(7, "unavailable"),
    PLATFORM_DISABLED(8, "platform_disabled"),
    TOOL_MISSING(9, "tool_missing"),
    TUNNEL_FAILED(10, "tunnel_failed"),
    DEV_SESSION_HELD(11, "dev_session_held"),
    DEV_SESSION_LOST(12, "dev_session_lost"),
    APP_EXITED(13, "app_exited"),
    CONNECTION_SCRIPTED(14, "connection_scripted"),
    RENDER_URL_EPHEMERAL(15, "render_url_ephemeral"),
    PLATFORM_TOML_SECRET(16, "platform_toml_secret"),
    PLATFORM_TOML_SCHEMA(17, "platform_toml_schema"),
    APP_NOT_LISTENING(18, "app_not_listening"),
    INTERRUPTED(130, "interrupted");

    private final int code;
    private final String word;

    Exit(int code, String word) {
        this.code = code;
        this.word = word;
    }

    public int code() {
        return code;
    }

    /** The row's name, as {@code apps dev --json} reports how it ended. */
    public String word() {
        return word;
    }
}
