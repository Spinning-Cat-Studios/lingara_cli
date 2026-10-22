package com.getlingara.cli.java.commands.apps;

import com.fasterxml.jackson.databind.JsonNode;
import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.auth.Calls;
import com.getlingara.cli.java.cli.AppsCli;
import com.getlingara.cli.java.cli.Choices.Lang;
import com.getlingara.cli.java.core.Assets;
import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.platform.AppRow;
import com.getlingara.cli.java.platform.Paths;
import com.getlingara.cli.java.platform.PlatformToml;
import com.getlingara.cli.java.platform.Schema;
import com.getlingara.cli.java.platform.Write;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code lingara apps create <name>} without {@code --manifest} (ADR 16.10.26ae D1): scaffold
 * offline, pin the project in {@code platform.toml}, then register it as a private app unless
 * {@code --no-register} or there is no session.
 */
final class Scaffold {
    private static final String DEFAULT_DESCRIPTION = "A Lingara app.";
    private static final String DEFAULT_SLOT = "home.side";

    private Scaffold() {}

    /** The description goes inside a string literal in every template language. */
    private static boolean descriptionOk(String text) {
        return text.chars().noneMatch(c -> "\"\\`$#{}".indexOf(c) >= 0 || Character.isISOControl(c));
    }

    private static boolean sliceOk(String kind) {
        return kind.matches("[a-z][a-z_]*");
    }

    private static Template.Keys keys(AppsCli.Create args) {
        if (!Schema.appNameOk(args.name)) {
            throw Fail.message(Exit.USAGE, "app_name_invalid", "name", args.name);
        }
        String description = args.description != null ? args.description : DEFAULT_DESCRIPTION;
        if (!descriptionOk(description)) {
            throw Fail.usage("--description may not hold \", \\, `, $, #, { or }, or a control character");
        }
        for (String slice : args.context) {
            if (!sliceOk(slice)) {
                throw Fail.usage("--context " + slice + " is not a context slice name");
            }
        }
        List<String> slots = new ArrayList<>();
        args.slot.forEach(s -> slots.add(s.wire()));
        if (slots.isEmpty()) {
            slots.add(DEFAULT_SLOT);
        }
        int port = args.port != null ? args.port : AppRow.DEFAULT_PORT;
        return new Template.Keys(args.name, Project.displayName(args.name), description, port, slots, args.context);
    }

    /**
     * Step 3's file: {@code --config} or {@code LINGARA_PLATFORM_TOML}, else the nearest above the
     * project's parent, else a new one inside the project.
     */
    private static Path pinFile(Ctx ctx, AppsCli.Create args, Path dir, Path cwd) {
        boolean explicit = args.config.config != null || ctx.app.env.get("LINGARA_PLATFORM_TOML") != null;
        Path found = explicit ? PlatformToml.locate(args.config.config, ctx.app.env, cwd)
                : dir.getParent() == null ? null : PlatformToml.findUpward(dir.getParent());
        if (found == null || !Files.isRegularFile(found)) {
            return dir.resolve(PlatformToml.FILE);
        }
        PlatformToml toml = Rows.loadAt(ctx, found, cwd);
        if (toml.apps().stream().anyMatch(r -> r.name().equals(args.name))) {
            throw Fail.usage(Paths.shown(found, cwd) + " already pins " + args.name);
        }
        return found;
    }

    private static boolean nonEmptyDir(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    static void run(Ctx ctx, AppsCli.Create args) {
        Template.Keys keys = keys(args);
        Path cwd = Rows.cwd();
        Path target = cwd.resolve(args.dir != null ? args.dir : Path.of(args.name));
        if (nonEmptyDir(target)) {
            throw Fail.message(Exit.USAGE, "dir_not_empty", "path", Paths.shown(target, cwd));
        }
        String lang = args.lang != null ? args.lang.wire() : Lang.TYPESCRIPT.wire();
        Template.Result rendered = Template.render(lang, args.bare ? "bare" : "default", keys);
        Path file = pinFile(ctx, args, target, cwd);
        try {
            Files.createDirectories(target);
        } catch (IOException e) {
            throw Fail.failure(target + ": " + e.getMessage());
        }
        Path dir = Paths.canonical(target);
        Template.writeAll(dir, rendered.files());
        Path base = Paths.canonical(file.getParent() != null ? file.getParent() : dir);
        Path pinned = base.resolve(file.getFileName());
        Template.Meta meta = rendered.meta();
        Write.pinRow(pinned, new Write.NewRow(args.name, Paths.relative(dir, base), keys.port(), meta.start(), meta.manifest()), null);
        Write.ensureGitignore(base);
        register(ctx, args, pinned, cwd);
    }

    /** Step 2, last: offline when {@code --no-register}, or with no session to use. */
    private static void register(Ctx ctx, AppsCli.Create args, Path file, Path cwd) {
        if (args.noRegister) {
            ctx.out.nothing();
            return;
        }
        if (Calls.ciPair(ctx) == null && ctx.app.stores.load(ctx.profile) == null) {
            ctx.out.note(Assets.message("apps_create_login_hint", "name", args.name));
            ctx.out.nothing();
            return;
        }
        PlatformToml toml = Rows.loadAt(ctx, file, cwd);
        AppRow row = Rows.resolve(toml, args.name, cwd);
        JsonNode manifest = Project.read(toml, row, cwd);
        Project.warn(ctx, manifest);
        Project.register(ctx, toml, row, manifest);
    }
}
