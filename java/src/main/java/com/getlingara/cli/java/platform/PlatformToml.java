package com.getlingara.cli.java.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.getlingara.cli.java.core.Env;
import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

/**
 * {@code platform.toml} (ADR 16.10.26ae D4): the gitignored, per-developer file naming the apps a
 * checkout works on. Found by {@code --config}, then {@code LINGARA_PLATFORM_TOML}, then by
 * walking up from the current directory as Cargo does. Read in a fixed order, so the first
 * refusal is the same in every kit: TOML syntax ({@code platform_toml_schema}), a secret-shaped
 * value or key ({@code platform_toml_secret}, {@link Secrets}), then the schema's rules ({@code
 * platform_toml_schema}, {@link Schema}). A tracked file is only a warning ({@link Git}).
 */
public record PlatformToml(Path file, String api, String profile, List<AppRow> apps) {
    public static final String FILE = "platform.toml";
    public static final String EXAMPLE = "platform.toml.example";

    /** The directory the file is in, which every row's {@code path} is relative to. */
    public Path dir() {
        Path parent = file.getParent();
        return parent == null ? Path.of(".") : parent;
    }

    public Path projectDir(AppRow row) {
        return dir().resolve(row.path());
    }

    /** The file's name as messages print it: relative to {@code cwd} where it can be. */
    public String shown(Path cwd) {
        return Paths.shown(file, cwd);
    }

    /** {@code --config}, then {@code LINGARA_PLATFORM_TOML}, then the nearest at or above {@code cwd}. */
    public static Path locate(Path config, Env env, Path cwd) {
        Path explicit = config != null ? config : env.get("LINGARA_PLATFORM_TOML") == null ? null : Path.of(env.get("LINGARA_PLATFORM_TOML"));
        if (explicit == null) {
            return findUpward(cwd);
        }
        return explicit.isAbsolute() ? explicit : cwd.resolve(explicit);
    }

    /** The nearest {@code platform.toml} in {@code from} or a directory above it; {@code null} for none. */
    public static Path findUpward(Path from) {
        for (Path dir = from; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(FILE);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    public static Fail schemaFail(String file, String reason) {
        return Fail.message(Exit.PLATFORM_TOML_SCHEMA, "platform_toml_schema", "file", file, "reason", reason);
    }

    /** Reads and checks {@code path}; {@code shown} is how messages name it. */
    public static PlatformToml load(Path path, String shown) {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw schemaFail(shown, String.valueOf(e.getMessage()));
        }
        TomlParseResult toml = Toml.parse(text);
        if (toml.hasErrors()) {
            throw schemaFail(shown, toml.errors().get(0).getMessage());
        }
        JsonNode value = TomlJson.table(toml);
        String key = Secrets.firstSecret(value);
        if (key != null) {
            throw Fail.message(Exit.PLATFORM_TOML_SECRET, "platform_toml_secret", "file", shown, "key", key);
        }
        String reason = Schema.check(value);
        if (reason != null) {
            throw schemaFail(shown, reason);
        }
        JsonNode platform = value.path("platform");
        List<AppRow> apps = new ArrayList<>();
        value.path("apps").forEach(row -> apps.add(AppRow.read(row)));
        return new PlatformToml(path, Json.text(platform, "api"), Json.text(platform, "profile"), apps);
    }
}
