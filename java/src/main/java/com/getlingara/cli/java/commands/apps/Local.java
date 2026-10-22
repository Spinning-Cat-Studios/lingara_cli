package com.getlingara.cli.java.commands.apps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.commands.Kind;
import com.getlingara.cli.java.commands.Objects;
import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Json;
import com.getlingara.cli.java.core.Table;
import com.getlingara.cli.java.platform.AppRow;
import com.getlingara.cli.java.platform.Paths;
import com.getlingara.cli.java.platform.PlatformToml;
import com.getlingara.cli.java.platform.Schema;
import com.getlingara.cli.java.platform.Write;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The local verbs (ADR 16.10.26ae D4, D9): {@code apps status} reads the dev version; {@code apps
 * pin}, {@code apps unpin} and {@code apps list --pinned} edit or read {@code platform.toml} alone
 * (pin's one read is {@code app.get} for a name).
 */
public final class Local {
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private Local() {}

    /** {@code apps status}: live with its lease, parked with its last URL, or the serving version. */
    public static void status(Ctx ctx, String name, Path config) {
        Rows.Opened opened = Rows.open(ctx, config);
        AppRow row = Rows.resolve(opened.toml(), name, opened.cwd());
        if (row.id() == null) {
            throw Fail.usage(row.name() + " is not registered yet: run lingara apps update " + row.name());
        }
        JsonNode view = DevApi.get(ctx, row.id());
        if (ctx.out.json()) {
            ctx.out.jsonValue(view);
            return;
        }
        ctx.out.print(statusLine(view) + "\n");
    }

    /** D9's three lines. The lease time is {@code HH:MM} of the server's UTC stamp. */
    public static String statusLine(JsonNode view) {
        String url = Json.text(view, "render_url");
        if (url == null) {
            return "dev: serving version";
        }
        if ("live".equals(Json.text(view.path("session"), "state"))) {
            String stamp = Json.text(view.path("session"), "lease_expires_at", "");
            String lease = stamp.length() >= 16 ? stamp.substring(11, 16) : "?";
            return "dev: live " + url + " (lease until " + lease + " UTC)";
        }
        return "dev: parked (last " + url + ")";
    }

    /** A platform object's name as a local handle: {@code Luba Dive Log} is {@code luba-dive-log}. */
    static String handleOf(String name) {
        StringBuilder lowered = new StringBuilder();
        name.chars().forEach(c -> lowered.append(c < 128 && Character.isLetterOrDigit(c) ? Character.toLowerCase((char) c) : '-'));
        List<String> words = new ArrayList<>();
        for (String w : lowered.toString().split("-")) {
            if (!w.isEmpty()) {
                words.add(w);
            }
        }
        return String.join("-", words);
    }

    /** The file pin writes, and the names it already pins: the one {@code open} would find, else a new one here. */
    private static Path pinTarget(Ctx ctx, Path config, Path cwd, List<String> names) {
        Path found = Rows.locate(ctx, config, cwd);
        if (found != null && Files.isRegularFile(found)) {
            Rows.loadAt(ctx, found, cwd).apps().forEach(r -> names.add(r.name()));
            return found;
        }
        return found != null ? found : cwd.resolve(PlatformToml.FILE);
    }

    /** {@code apps pin <id|name> [--path <dir>]}: a row in platform.toml and, when it has none of that name, in the example. */
    public static void pin(Ctx ctx, String app, Path projectPath, Path config) {
        Path cwd = Rows.cwd();
        String name = app;
        String id = null;
        if (UUID.matcher(app).matches()) {
            name = handleOf(Json.text(Objects.get(ctx, Kind.APP, app), "name", ""));
            id = app;
        } else if (projectPath == null) {
            throw Fail.usage("pinning " + app + " by name needs --path <dir>");
        }
        if (!Schema.appNameOk(name)) {
            throw Fail.message(Exit.USAGE, "app_name_invalid", "name", name);
        }
        List<String> names = new ArrayList<>();
        Path file = pinTarget(ctx, config, cwd, names);
        if (names.contains(name)) {
            throw Fail.usage(Paths.shown(file, cwd) + " already pins " + name);
        }
        Path base = file.getParent() != null ? file.getParent() : cwd;
        Path project = Paths.canonical(cwd.resolve(projectPath != null ? projectPath : Path.of(".")));
        Write.pinRow(file, new Write.NewRow(name, Paths.relative(project, base), AppRow.DEFAULT_PORT, null, null), id);
        ctx.out.nothing();
    }

    /** {@code apps unpin <name>}: platform.toml only; the shared example changes by review. */
    public static void unpin(Ctx ctx, String name, Path config) {
        Rows.Opened opened = Rows.open(ctx, config);
        if (!Write.removeRow(opened.toml().file(), name)) {
            throw Fail.message(Exit.USAGE, "app_row_unknown", "file", opened.toml().shown(opened.cwd()), "value", name);
        }
        ctx.out.nothing();
    }

    /** {@code apps list --pinned}: the rows, as JSON or as a NAME/ID/PATH/PORT table. */
    public static void listPinned(Ctx ctx, Path config) {
        Rows.Opened opened = Rows.open(ctx, config);
        ArrayNode rows = Json.array();
        for (AppRow r : opened.toml().apps()) {
            ObjectNode row = rows.addObject();
            row.put("name", r.name());
            if (r.id() == null) {
                row.putNull("id");
            } else {
                row.put("id", r.id());
            }
            row.put("path", r.path());
            row.put("port", r.port());
        }
        if (ctx.out.json()) {
            ctx.out.jsonValue(rows);
            return;
        }
        List<List<String>> cells = new ArrayList<>();
        rows.forEach(r -> cells.add(List.of(Table.cell(r.get("name")), Table.cell(r.get("id")), Table.cell(r.get("path")), Table.cell(r.get("port")))));
        ctx.out.print(Table.render(List.of("NAME", "ID", "PATH", "PORT"), cells));
    }
}
