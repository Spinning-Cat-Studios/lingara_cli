package com.getlingara.cli.java.store;

import com.getlingara.cli.java.core.Env;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/**
 * The config dir and {@code config.toml} (16.10.26ad D4). The dir is {@code LINGARA_CONFIG_DIR},
 * else {@code $XDG_CONFIG_HOME/lingara}, else {@code ~/.config/lingara} on macOS and Linux alike
 * (never {@code ~/Library/Application Support}), and {@code %APPDATA%\lingara} on Windows. {@code
 * config.toml} holds only non-secret per-profile settings: {@code [profiles.<name>]} with {@code
 * api_url} and {@code output}.
 */
public final class ConfigDir {
    private ConfigDir() {}

    /** One profile's settings; each {@code null} when unset. */
    public record ProfileConfig(String apiUrl, String output) {
        static final ProfileConfig NONE = new ProfileConfig(null, null);
    }

    public static Path of(Env env) {
        if (env.get("LINGARA_CONFIG_DIR") != null) {
            return Path.of(env.get("LINGARA_CONFIG_DIR"));
        }
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            return env.get("APPDATA") == null ? null : Path.of(env.get("APPDATA"), "lingara");
        }
        if (env.get("XDG_CONFIG_HOME") != null) {
            return Path.of(env.get("XDG_CONFIG_HOME"), "lingara");
        }
        String home = env.get("HOME") != null ? env.get("HOME") : System.getProperty("user.home");
        return home == null ? null : Path.of(home, ".config", "lingara");
    }

    /** The profile's settings; a missing or unreadable file is no settings. */
    public static ProfileConfig profile(Path dir, String profile) {
        Path file = dir == null ? null : dir.resolve("config.toml");
        if (file == null || !Files.isRegularFile(file)) {
            return ProfileConfig.NONE;
        }
        TomlParseResult toml;
        try {
            toml = Toml.parse(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return ProfileConfig.NONE;
        }
        TomlTable profiles = toml.hasErrors() ? null : toml.getTable("profiles");
        Object entry = profiles == null ? null : profiles.get(java.util.List.of(profile));
        if (!(entry instanceof TomlTable table)) {
            return ProfileConfig.NONE;
        }
        return new ProfileConfig(text(table, "api_url"), text(table, "output"));
    }

    private static String text(TomlTable table, String key) {
        Object value = table.get(java.util.List.of(key));
        return value instanceof String s ? s : null;
    }
}
