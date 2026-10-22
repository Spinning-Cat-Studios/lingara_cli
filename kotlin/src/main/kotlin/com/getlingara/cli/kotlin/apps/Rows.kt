// Opening `platform.toml` for a command and picking its row (ADR 16.10.26ae
// D4, D7 step 1). Opening runs every refusal before any request, warns once
// when git tracks the file, and applies its `[platform]` table: `api` unless
// `LINGARA_API_URL` is set, `profile` unless `--profile` or
// `LINGARA_PROFILE` chose one.
package com.getlingara.cli.kotlin.apps

import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.core.Assets
import com.getlingara.cli.kotlin.core.Exit
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.project.AppRow
import com.getlingara.cli.kotlin.project.FILE
import com.getlingara.cli.kotlin.project.Git
import com.getlingara.cli.kotlin.project.PlatformToml
import com.getlingara.cli.kotlin.project.Paths
import java.nio.file.Files
import java.nio.file.Path

object Rows {
    /** The process's directory, through any symlink, so a row's path compares. A JVM never changes it. */
    fun cwd(): Path = Paths.real(Path.of(System.getProperty("user.dir")))

    /** The file `--config`, `LINGARA_PLATFORM_TOML` or the walk up names, if any. */
    fun locate(ctx: Ctx, config: String?, cwd: Path): Path? = PlatformToml.locate(config, ctx.app.env, cwd)

    /** Reads and checks the file at [path], warns if it is tracked, and applies its `[platform]` table. */
    fun loadAt(ctx: Ctx, path: Path, cwd: Path): PlatformToml {
        val shown = Paths.shown(path, cwd)
        val toml = PlatformToml.load(path, shown)
        if (Git.tracked(path)) ctx.out.note(Assets.message("platform_toml_tracked", "path" to shown))
        if (ctx.app.env.get("LINGARA_API_URL") == null) toml.api?.let { ctx.app.http.bases.api = it }
        if (!ctx.profileChosen) toml.profile?.let { ctx.profile = it }
        return toml
    }

    /** The command's `platform.toml`, which must exist. */
    fun open(ctx: Ctx, config: String?): Pair<PlatformToml, Path> {
        val cwd = cwd()
        val path = locate(ctx, config, cwd)
            ?: throw Fail.usage("no $FILE at or above $cwd: run lingara apps create, or lingara apps pin")
        if (!Files.isRegularFile(path)) throw PlatformToml.schemaFail(path.toString(), "the file does not exist")
        return loadAt(ctx, path, cwd) to cwd
    }

    private fun unknown(toml: PlatformToml, cwd: Path, value: String) =
        Fail.message(Exit.USAGE, "app_row_unknown", "file" to toml.shown(cwd), "value" to value)

    /** The row named (by `name` or `id`), or with none named: the only row, or the one whose project contains [cwd] (the deepest, when they nest). */
    fun resolve(toml: PlatformToml, wanted: String?, cwd: Path): AppRow {
        if (wanted != null) return toml.apps.firstOrNull { it.name == wanted || it.id == wanted } ?: throw unknown(toml, cwd, wanted)
        toml.apps.singleOrNull()?.let { return it }
        val containing = toml.apps.mapNotNull { r ->
            val dir = Paths.real(toml.projectDir(r))
            if (cwd.startsWith(dir)) dir.nameCount to r else null
        }.sortedByDescending { it.first }
        val first = containing.firstOrNull()
        return when {
            first != null && (containing.size == 1 || containing[1].first < first.first) -> first.second
            toml.apps.isEmpty() -> throw unknown(toml, cwd, "(no app is pinned)")
            else -> throw Fail.message(Exit.USAGE, "app_row_ambiguous", "file" to toml.shown(cwd), "names" to toml.apps.joinToString(", ") { it.name })
        }
    }
}
