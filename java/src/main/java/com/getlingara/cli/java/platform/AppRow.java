package com.getlingara.cli.java.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.getlingara.cli.java.core.Json;
import java.util.TreeMap;
import java.util.List;
import java.util.Map;

/**
 * One {@code [[apps]]} row of {@code platform.toml} (16.10.26ae D4). The example's placeholder id
 * reads as no id; a missing {@code path} is {@code .} and a missing {@code port} 8787.
 */
public record AppRow(
        String name,
        String id,
        String path,
        int port,
        String start,
        String renderPath,
        String manifest,
        String manifestCommand,
        List<String> credentials,
        String envFile,
        Map<String, String> env) {

    /** The example's id, which every kit reads as no id (spec/templates.md). */
    public static final String PLACEHOLDER_ID = "00000000-0000-0000-0000-000000000000";

    public static final int DEFAULT_PORT = 8787;

    static AppRow read(JsonNode v) {
        String id = Json.text(v, "id");
        JsonNode port = v.path("port");
        Map<String, String> env = new TreeMap<>();
        v.path("env").properties().forEach(e -> {
            if (e.getValue().isTextual()) {
                env.put(e.getKey(), e.getValue().asText());
            }
        });
        return new AppRow(
                Json.text(v, "name", ""),
                PLACEHOLDER_ID.equals(id) ? null : id,
                Json.text(v, "path", "."),
                port.isIntegralNumber() && port.asLong() >= 0 && port.asLong() <= 65535 ? port.asInt() : DEFAULT_PORT,
                Json.text(v, "start"),
                Json.text(v, "render_path"),
                Json.text(v, "manifest"),
                Json.text(v, "manifest_command"),
                Json.strings(v.path("credentials")),
                Json.text(v, "env_file"),
                env);
    }
}
