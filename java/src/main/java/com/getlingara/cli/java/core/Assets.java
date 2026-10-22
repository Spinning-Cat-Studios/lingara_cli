package com.getlingara.cli.java.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

/**
 * Every shared file this kit reads, from its own classpath (ADR 16.10.26af D8): {@code
 * endpoints.json} (where production is), {@code messages.en.json} (what a prompt says, byte for
 * byte), {@code tools.toml} (cloudflared's floor) and the app templates. All are committed copies
 * under {@code lingara/}, held equal to their sources by {@code make check-kit-assets}, because a
 * published jar carries only its own directory. The build adds {@code kit.properties} (the
 * version) and {@code templates.idx} (a jar cannot be listed) beside the classes.
 */
public final class Assets {
    private static final String ROOT = "/lingara/";
    private static final String KIT = "/com/getlingara/cli/java/";

    private Assets() {}

    /** A template file: its path under {@code <lang>/<variant>/}, and its bytes. */
    public record TemplateFile(String path, byte[] bytes) {}

    /** {@code endpoints.json}'s three values. */
    public record Endpoints(String clientId, String apiUrl, String platformUrl) {}

    private static final class Texts {
        static final Map<String, String> BY_ID = load();

        private static Map<String, String> load() {
            Map<String, String> out = new HashMap<>();
            JsonNode file = Json.parse(read(ROOT + "messages.en.json"));
            if (file != null) {
                file.path("messages").forEach(m -> out.put(m.path("id").asText(), m.path("text").asText()));
            }
            return out;
        }
    }

    public static byte[] read(String resource) {
        try (InputStream in = Assets.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new UncheckedIOException(new IOException("this lingara carries no " + resource));
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readText(String resource) {
        return new String(read(resource), StandardCharsets.UTF_8);
    }

    /**
     * The message {@code id} with each {@code {name}} filled from {@code vars} (name, value, …); an
     * id the file lacks prints as itself, which a test pins never to happen.
     */
    public static String message(String id, String... vars) {
        String text = Texts.BY_ID.getOrDefault(id, id);
        for (int i = 0; i + 1 < vars.length; i += 2) {
            text = text.replace("{" + vars[i] + "}", vars[i + 1]);
        }
        return text;
    }

    public static boolean hasMessage(String id) {
        return Texts.BY_ID.containsKey(id);
    }

    public static Endpoints endpoints() {
        JsonNode file = Json.parse(read(ROOT + "endpoints.json"));
        return new Endpoints(
                Json.text(file, "client_id", "lingara-cli"),
                Json.text(file, "api_url", "https://api.getlingara.com"),
                Json.text(file, "platform_url", "https://platform.getlingara.com"));
    }

    /** {@code tools.toml}'s {@code [cloudflared] min_version}, as dotted numbers. */
    public static List<Long> cloudflaredMinVersion() {
        TomlParseResult tools = Toml.parse(readText(ROOT + "tools.toml"));
        String text = tools.getString("cloudflared.min_version");
        List<Long> out = new ArrayList<>();
        for (String part : (text == null ? "" : text).split("\\.")) {
            try {
                out.add(Long.parseLong(part));
            } catch (NumberFormatException e) {
                // A non-number field is not part of the version, as in the reference kit.
            }
        }
        return out;
    }

    /** The kit's version: the repo's VERSION, stamped in at build time. */
    public static String version() {
        Properties kit = new Properties();
        try (InputStream in = Assets.class.getResourceAsStream(KIT + "kit.properties")) {
            if (in != null) {
                kit.load(in);
            }
        } catch (IOException e) {
            // An unreadable stamp reads as 0.0.0 rather than failing every command.
        }
        return kit.getProperty("version", "0.0.0");
    }

    /** Every file of one template ({@code <lang>/<variant>/}), path relative to it. */
    public static List<TemplateFile> template(String lang, String variant) {
        String prefix = lang + "/" + variant + "/";
        List<TemplateFile> out = new ArrayList<>();
        for (String path : readText(KIT + "templates.idx").split("\n")) {
            if (path.startsWith(prefix)) {
                out.add(new TemplateFile(path.substring(prefix.length()), read(ROOT + "templates/apps/" + path)));
            }
        }
        return out;
    }
}
