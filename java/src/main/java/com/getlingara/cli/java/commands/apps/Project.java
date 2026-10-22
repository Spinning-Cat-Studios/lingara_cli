package com.getlingara.cli.java.commands.apps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.commands.Creation;
import com.getlingara.cli.java.commands.Kind;
import com.getlingara.cli.java.core.Assets;
import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Json;
import com.getlingara.cli.java.literal.ConnectionLiteral;
import com.getlingara.cli.java.literal.Refusal;
import com.getlingara.cli.java.platform.AppRow;
import com.getlingara.cli.java.platform.Paths;
import com.getlingara.cli.java.platform.PlatformToml;
import com.getlingara.cli.java.platform.Write;
import com.getlingara.cli.java.supervise.Shell;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A project's manifest and its registration (ADR 16.10.26ae D1 step 2, D6 steps 1, 4 and 5): read
 * from {@code lingara/connection.js} by the literal reader, or from {@code manifest_command}'s
 * stdout; checked; its warnings printed; and, for a row with no id, registered as a private app.
 */
final class Project {
    private static final String DEFAULT_MANIFEST = "lingara/connection.js";

    private Project() {}

    /** The manifest's source as messages name it, relative to {@code cwd}. */
    static String source(PlatformToml toml, AppRow row, Path cwd) {
        if (row.manifestCommand() != null) {
            return row.manifestCommand();
        }
        Path file = toml.projectDir(row).resolve(row.manifest() != null ? row.manifest() : DEFAULT_MANIFEST);
        return Paths.shown(file, cwd);
    }

    /** The wire manifest the project describes, checked before any request. */
    static JsonNode read(PlatformToml toml, AppRow row, Path cwd) {
        Path project = toml.projectDir(row);
        String shown = source(toml, row, cwd);
        JsonNode manifest;
        if (row.manifestCommand() != null) {
            manifest = runCommand(row.manifestCommand(), project);
        } else {
            Path file = project.resolve(row.manifest() != null ? row.manifest() : DEFAULT_MANIFEST);
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw Fail.usage(shown + ": " + e.getMessage());
            }
            try {
                manifest = ConnectionLiteral.appManifest(text);
            } catch (Refusal r) {
                throw r.fail(shown);
            }
        }
        String rule = ManifestRules.brokenRule(manifest);
        if (rule != null) {
            throw new Fail(Exit.USAGE, "manifest_invalid", shown + ": the manifest breaks the " + rule + " rule");
        }
        return manifest;
    }

    /** D3: {@code manifest_command} through the platform shell, in the project; stdout is the manifest, stderr the developer's. */
    private static JsonNode runCommand(String command, Path dir) {
        byte[] stdout;
        int status;
        try {
            Process p = Shell.shell(command).directory(dir.toFile())
                    .redirectInput(ProcessBuilder.Redirect.PIPE)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            p.getOutputStream().close();
            stdout = p.getInputStream().readAllBytes();
            status = p.waitFor();
        } catch (IOException e) {
            throw Fail.failure(command + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw Fail.failure(command + ": interrupted");
        }
        if (status != 0) {
            throw Fail.failure(command + " exited " + status + "; fix it, then run lingara apps update");
        }
        String text = new String(stdout, StandardCharsets.UTF_8);
        JsonNode manifest = Json.parse(text);
        if (manifest == null) {
            throw Fail.usage(command + " printed no manifest JSON: " + Json.parseError(text));
        }
        return manifest;
    }

    /** D6 step 5: one {@code manifest_warning} line each, never a failure. */
    static List<String> warn(Ctx ctx, JsonNode manifest) {
        List<String> codes = ManifestRules.warnings(manifest);
        codes.forEach(code -> ctx.out.note(Assets.message("manifest_warning", "code", code, "text", ManifestRules.warningText(code))));
        return codes;
    }

    /** The display name D2 derives: each {@code -} a space, each word's first ASCII letter upper-cased. */
    static String displayName(String name) {
        List<String> words = new ArrayList<>();
        for (String w : name.split("-", -1)) {
            words.add(w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        return String.join(" ", words);
    }

    /**
     * D1 step 2 / D6 step 4: the private OAuth client, then the app under it through the
     * registration form's own steps, the id written into the row. Prints the app as the
     * registration form does.
     */
    static JsonNode register(Ctx ctx, PlatformToml toml, AppRow row, JsonNode manifest) {
        String title = displayName(row.name());
        ObjectNode clientBody = Json.object();
        clientBody.put("client_type", "confidential");
        clientBody.putArray("allowed_scopes");
        clientBody.put("billing_mode", "allowance");
        clientBody.putArray("redirect_uris");
        Creation made = new Creation();
        JsonNode app = made.conclude(ctx, () -> {
            String clientId = Json.text(made.object(ctx, Kind.OAUTH_CLIENT, title, null, clientBody), "id", "");
            ObjectNode body = Json.object();
            body.set("manifest", manifest);
            JsonNode created = made.object(ctx, Kind.APP, title, clientId, body);
            return new Creation.Last(Kind.APP, Json.text(created, "id", ""));
        });
        Write.setId(toml.file(), row.name(), Json.text(app, "id", ""));
        return app;
    }
}
