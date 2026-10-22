package com.getlingara.cli.java.commands.apps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.auth.Calls;
import com.getlingara.cli.java.cli.AppsMoreCli;
import com.getlingara.cli.java.cli.Choices.TunnelKind;
import com.getlingara.cli.java.core.Assets;
import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.core.Json;
import com.getlingara.cli.java.platform.AppRow;
import com.getlingara.cli.java.platform.Git;
import com.getlingara.cli.java.platform.Paths;
import com.getlingara.cli.java.platform.PlatformToml;
import com.getlingara.cli.java.supervise.Cloudflared;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code lingara apps dev [name]} (ADR 16.10.26ae D7), steps 1 and 2: resolve the row and run
 * every refusal offline, so a refused run sends no request; then register a row with no id (D6
 * step 4) and hand over to {@link DevRun}. {@code --reset} is here too: one {@code PATCH
 * {render_url: null}} and nothing else.
 */
public final class Dev {
    private Dev() {}

    /**
     * Everything a session needs, decided before the first request. {@code cloudflared} is the
     * binary to spawn, or {@code null} with {@code fixedUrl} the {@code --render-url}; {@code
     * probe} is {@code LINGARA_TUNNEL_PROBE_URL}, where readiness is probed, never what is pointed
     * at.
     */
    record Plan(String name, String id, Path dir, int port, String start, String renderPath, Path cloudflared, String fixedUrl,
            boolean takeOver, boolean client, List<String[]> env, Path writeEnv, boolean showEnv, String probe) {
        Plan withId(String registered) {
            return new Plan(name, registered, dir, port, start, renderPath, cloudflared, fixedUrl, takeOver, client, env, writeEnv,
                    showEnv, probe);
        }
    }

    public static void run(Ctx ctx, AppsMoreCli.Dev args) {
        Rows.Opened opened = Rows.open(ctx, args.config.config);
        AppRow row = Rows.resolve(opened.toml(), args.name, opened.cwd());
        if (args.reset) {
            reset(ctx, row);
            return;
        }
        Plan plan = preflight(ctx, args, opened.toml(), row, opened.cwd());
        String id = row.id();
        if (id == null) {
            JsonNode app = Project.register(ctx, opened.toml(), row, Project.read(opened.toml(), row, opened.cwd()));
            // The registration printed the app; apps dev prints one value, at its exit.
            ctx.out.clearStdout();
            id = Json.text(app, "id", "");
        }
        DevRun.session(ctx, plan.withId(id));
    }

    private static void ignoredOrRefuse(Path path, Path cwd) {
        if (!Git.ignored(path)) {
            throw Fail.message(Exit.USAGE, "path_not_ignored", "path", Paths.shown(path, cwd));
        }
    }

    /** {@code KEY=VALUE} lines; blank lines, {@code #} comments and an {@code export } prefix are skipped. */
    static List<String[]> envFile(Path path) {
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw Fail.usage(path + ": " + e.getMessage());
        }
        List<String[]> out = new ArrayList<>();
        for (String raw : text.split("\n", -1)) {
            String[] pair = envLine(raw);
            if (pair != null) {
                out.add(pair);
            }
        }
        return out;
    }

    /** One line's {@code {key, value}}, unquoted; {@code null} for a blank line or a comment. */
    private static String[] envLine(String raw) {
        String line = raw.strip();
        line = line.startsWith("export ") ? line.substring("export ".length()) : line;
        int eq = line.indexOf('=');
        if (eq < 0 || line.startsWith("#") || line.substring(0, eq).strip().isEmpty()) {
            return null;
        }
        String v = line.substring(eq + 1).strip();
        return new String[] {line.substring(0, eq).strip(), quoted(v) ? v.substring(1, v.length() - 1) : v};
    }

    private static boolean quoted(String v) {
        return v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"));
    }

    /** The binary to spawn, or {@code null} for {@code --tunnel none --render-url}: the two come together. */
    private static Path target(Ctx ctx, AppsMoreCli.Dev args) {
        boolean none = args.tunnel == TunnelKind.NONE;
        if (none && args.renderUrl == null) {
            throw Fail.usage("--tunnel none needs --render-url");
        }
        if (args.renderUrl != null && !none) {
            throw Fail.usage("--render-url needs --tunnel none");
        }
        return none ? null : Cloudflared.find(ctx.app.env);
    }

    /** Step 2: every refusal before the network. The session record is read, never refreshed. */
    private static Plan preflight(Ctx ctx, AppsMoreCli.Dev args, PlatformToml toml, AppRow row, Path cwd) {
        if (row.start() == null) {
            throw Fail.usage(row.name() + "'s row has no start command");
        }
        Path dir = toml.projectDir(row);
        List<String[]> env = new ArrayList<>();
        row.env().forEach((k, v) -> env.add(new String[] {k, v}));
        if (row.envFile() != null) {
            Path path = dir.resolve(row.envFile());
            ignoredOrRefuse(path, cwd);
            env.addAll(envFile(path));
        }
        Path writeEnv = args.writeEnv == null ? null : cwd.resolve(args.writeEnv);
        if (writeEnv != null) {
            ignoredOrRefuse(writeEnv, cwd);
        }
        Path cloudflared = target(ctx, args);
        if (Calls.ciPair(ctx) == null && ctx.app.stores.load(ctx.profile) == null) {
            throw Fail.auth("not signed in: run `lingara login`");
        }
        int port = args.port != null ? args.port : row.port();
        String renderPath = row.renderPath() != null ? row.renderPath() : "/";
        return new Plan(row.name(), "", dir, port, row.start(), renderPath, cloudflared, args.renderUrl, args.takeOver,
                row.credentials().contains("client_credentials"), env, writeEnv, args.showEnv, ctx.app.env.get("LINGARA_TUNNEL_PROBE_URL"));
    }

    /** {@code --reset}: the owner's install back on the serving version. A live session refuses it. */
    private static void reset(Ctx ctx, AppRow row) {
        if (row.id() == null) {
            throw Fail.usage(row.name() + " is not registered yet: run lingara apps update " + row.name() + " first");
        }
        try {
            DevApi.patchWithRetry(ctx, row.id(), draft -> {
                ObjectNode body = Json.object();
                body.putNull("render_url");
                body.set("draft_version", draft);
                return body;
            });
        } catch (Fail f) {
            throw f.exit() == Exit.DEV_SESSION_HELD ? Fail.message(Exit.DEV_SESSION_HELD, "apps_dev_reset_held", "name", row.name()) : f;
        }
        ctx.out.note(Assets.message("apps_dev_reset", "name", row.name()));
        ctx.out.nothing();
    }
}
