package com.getlingara.cli.java.commands.apps;

import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.core.Assets;
import com.getlingara.cli.java.core.Exit;
import com.getlingara.cli.java.core.Fail;
import com.getlingara.cli.java.platform.AppRow;
import com.getlingara.cli.java.platform.Git;
import com.getlingara.cli.java.platform.Paths;
import com.getlingara.cli.java.platform.PlatformToml;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Opening {@code platform.toml} for a command and picking its row (ADR 16.10.26ae D4, D7 step 1).
 * Opening runs every refusal before any request, warns once when git tracks the file, and applies
 * its {@code [platform]} table: {@code api} unless {@code LINGARA_API_URL} is set, {@code profile}
 * unless {@code --profile} or {@code LINGARA_PROFILE} chose one.
 */
public final class Rows {
    private Rows() {}

    /** An opened file and the directory the command runs in. */
    public record Opened(PlatformToml toml, Path cwd) {}

    /** The process's directory, through any symlink, so a row's path compares. */
    public static Path cwd() {
        return Paths.canonical(Path.of("").toAbsolutePath());
    }

    /** The file {@code --config}, {@code LINGARA_PLATFORM_TOML} or the walk up names; {@code null} for none. */
    public static Path locate(Ctx ctx, Path config, Path cwd) {
        return PlatformToml.locate(config, ctx.app.env, cwd);
    }

    /** Reads and checks the file at {@code path}, warns if it is tracked, and applies its {@code [platform]} table. */
    public static PlatformToml loadAt(Ctx ctx, Path path, Path cwd) {
        String shown = Paths.shown(path, cwd);
        PlatformToml toml = PlatformToml.load(path, shown);
        if (Git.tracked(path)) {
            ctx.out.note(Assets.message("platform_toml_tracked", "path", shown));
        }
        if (ctx.app.env.get("LINGARA_API_URL") == null && toml.api() != null) {
            ctx.app.http.api(toml.api());
        }
        if (!ctx.profileChosen && toml.profile() != null) {
            ctx.profile = toml.profile();
        }
        return toml;
    }

    /** The command's {@code platform.toml}, which must exist. */
    public static Opened open(Ctx ctx, Path config) {
        Path cwd = cwd();
        Path path = locate(ctx, config, cwd);
        if (path == null) {
            throw Fail.usage("no " + PlatformToml.FILE + " at or above " + cwd + ": run lingara apps create, or lingara apps pin");
        }
        if (!Files.isRegularFile(path)) {
            throw PlatformToml.schemaFail(path.toString(), "the file does not exist");
        }
        return new Opened(loadAt(ctx, path, cwd), cwd);
    }

    /** The one row whose project holds {@code cwd} most deeply; {@code null} for none or a tie. */
    private static AppRow deepestContaining(PlatformToml toml, Path cwd) {
        AppRow deepest = null;
        int depth = -1;
        int atDepth = 0;
        for (AppRow row : toml.apps()) {
            Path dir = Paths.canonical(toml.projectDir(row));
            int d = dir.getNameCount();
            if (!cwd.startsWith(dir) || d < depth) {
                continue;
            }
            atDepth = d == depth ? atDepth + 1 : 1;
            if (d > depth) {
                deepest = row;
                depth = d;
            }
        }
        return atDepth == 1 ? deepest : null;
    }

    private static Fail unknown(PlatformToml toml, Path cwd, String value) {
        return Fail.message(Exit.USAGE, "app_row_unknown", "file", toml.shown(cwd), "value", value);
    }

    /**
     * The row named (by {@code name} or {@code id}), or with none named: the only row, or the one
     * whose project contains {@code cwd} (the deepest, when they nest).
     */
    public static AppRow resolve(PlatformToml toml, String wanted, Path cwd) {
        if (wanted != null) {
            return toml.apps().stream()
                    .filter(r -> r.name().equals(wanted) || wanted.equals(r.id()))
                    .findFirst()
                    .orElseThrow(() -> unknown(toml, cwd, wanted));
        }
        if (toml.apps().size() == 1) {
            return toml.apps().get(0);
        }
        AppRow deepest = deepestContaining(toml, cwd);
        if (deepest != null) {
            return deepest;
        }
        if (toml.apps().isEmpty()) {
            throw unknown(toml, cwd, "(no app is pinned)");
        }
        List<String> names = new ArrayList<>();
        toml.apps().forEach(r -> names.add(r.name()));
        throw Fail.message(Exit.USAGE, "app_row_ambiguous", "file", toml.shown(cwd), "names", String.join(", ", names));
    }
}
