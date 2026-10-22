package com.getlingara.cli.java.commands.apps;

import com.getlingara.cli.java.core.Assets;
import com.getlingara.cli.java.core.Fail;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

/**
 * Rendering a template (ADR 16.10.26ae D2, D3; spec/templates.md): the fixed {@code {{key}}} set,
 * the array syntax each language's bare template uses, the two renaming rules, and {@code
 * template.toml}, read and never written.
 */
final class Template {
    private static final String META = "template.toml";

    private Template() {}

    /** What the scaffold substitutes, each derived once (spec/templates.md). */
    record Keys(String name, String displayName, String description, int port, List<String> slots, List<String> context) {}

    /** {@code template.toml}: the row's start command, and {manifest key, value} or {@code null}. */
    record Meta(String start, String[] manifest) {}

    /** One file, at its path in the project. */
    record Rendered(String path, byte[] bytes) {}

    record Result(List<Rendered> files, Meta meta) {}

    /** {@code ["a", "b"]} in the syntax the template's language writes an array in. */
    static String array(String lang, String variant, List<String> items) {
        List<String> quoted = new ArrayList<>();
        items.forEach(i -> quoted.add("\"" + i + "\""));
        String inner = String.join(", ", quoted);
        if (!variant.equals("bare")) {
            return "[" + inner + "]";
        }
        return switch (lang) {
            case "go" -> "[]string{" + inner + "}";
            case "java" -> "List.of(" + inner + ")";
            case "kotlin" -> "listOf(" + inner + ")";
            default -> "[" + inner + "]";
        };
    }

    /** A segment starting {@code _} is written with {@code .}; a {@code .tmpl} suffix is dropped. */
    static String projectPath(String relative) {
        List<String> segments = new ArrayList<>();
        for (String s : relative.split("/", -1)) {
            segments.add(s.startsWith("_") ? "." + s.substring(1) : s);
        }
        int last = segments.size() - 1;
        if (segments.get(last).endsWith(".tmpl")) {
            String name = segments.get(last);
            segments.set(last, name.substring(0, name.length() - ".tmpl".length()));
        }
        return String.join("/", segments);
    }

    static String substitute(String text, String lang, String variant, Keys keys) {
        return text.replace("{{name}}", keys.name())
                .replace("{{display_name}}", keys.displayName())
                .replace("{{description}}", keys.description())
                .replace("{{port}}", Integer.toString(keys.port()))
                .replace("{{slots}}", array(lang, variant, keys.slots()))
                .replace("{{context}}", array(lang, variant, keys.context()));
    }

    /** Every file of {@code <lang>/<variant>}, rendered, and its {@code template.toml}. */
    static Result render(String lang, String variant, Keys keys) {
        List<Assets.TemplateFile> files = Assets.template(lang, variant);
        if (files.isEmpty()) {
            throw Fail.failure("this lingara carries no " + lang + " " + variant + " template");
        }
        Meta meta = new Meta(null, null);
        List<Rendered> out = new ArrayList<>();
        for (Assets.TemplateFile file : files) {
            String text = new String(file.bytes(), StandardCharsets.UTF_8);
            if (file.path().equals(META)) {
                meta = parseMeta(text);
            } else {
                out.add(new Rendered(projectPath(file.path()), substitute(text, lang, variant, keys).getBytes(StandardCharsets.UTF_8)));
            }
        }
        return new Result(out, meta);
    }

    private static Meta parseMeta(String text) {
        TomlParseResult toml = Toml.parse(text);
        if (toml.hasErrors()) {
            throw Fail.failure(META + ": " + toml.errors().get(0).getMessage());
        }
        String manifest = toml.getString("manifest");
        String command = toml.getString("manifest_command");
        String[] source = manifest != null ? new String[] {"manifest", manifest}
                : command != null ? new String[] {"manifest_command", command} : null;
        return new Meta(toml.getString("start"), source);
    }

    /** Writes every file under {@code dir}, creating directories as it goes. */
    static void writeAll(Path dir, List<Rendered> files) {
        for (Rendered file : files) {
            Path path = dir.resolve(file.path());
            try {
                Files.createDirectories(path.getParent());
                Files.write(path, file.bytes());
            } catch (IOException e) {
                throw Fail.failure(path + ": " + e.getMessage());
            }
        }
    }
}
