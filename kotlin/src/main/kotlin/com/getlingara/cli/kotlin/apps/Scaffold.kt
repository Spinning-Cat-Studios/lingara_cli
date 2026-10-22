// `lingara apps create <name>` without `--manifest` (ADR 16.10.26ae D1):
// scaffold offline, pin the project in `platform.toml`, then register it as
// a private app unless `--no-register` or there is no session.
package com.getlingara.cli.kotlin.apps

import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.auth.ciPair
import com.getlingara.cli.kotlin.core.Assets
import com.getlingara.cli.kotlin.core.Exit
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.project.DEFAULT_PORT
import com.getlingara.cli.kotlin.project.FILE
import com.getlingara.cli.kotlin.project.NewRow
import com.getlingara.cli.kotlin.project.Paths
import com.getlingara.cli.kotlin.project.PlatformToml
import com.getlingara.cli.kotlin.project.Rules
import com.getlingara.cli.kotlin.project.Write
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

object Scaffold {
    private const val DEFAULT_DESCRIPTION = "A Lingara app."
    private const val DEFAULT_SLOT = "home.side"

    /** The description goes inside a string literal in every template language. */
    private fun descriptionOk(text: String) = text.none { it in "\"\\`$#{}" || it.isISOControl() }

    private fun sliceOk(kind: String) = kind.firstOrNull() in 'a'..'z' && kind.all { it in 'a'..'z' || it == '_' }

    private fun keys(args: CreateArgs): Keys {
        if (!Rules.appNameOk(args.name)) throw Fail.message(Exit.USAGE, "app_name_invalid", "name" to args.name)
        val description = args.description ?: DEFAULT_DESCRIPTION
        if (!descriptionOk(description)) throw Fail.usage("--description may not hold \", \\, `, $, #, { or }, or a control character")
        args.context.firstOrNull { !sliceOk(it) }?.let { throw Fail.usage("--context $it is not a context slice name") }
        val slots = args.slot.ifEmpty { listOf(DEFAULT_SLOT) }
        return Keys(args.name, Project.displayName(args.name), description, args.port ?: DEFAULT_PORT, slots, args.context)
    }

    /**
     * Step 3's file: `--config` or `LINGARA_PLATFORM_TOML`, else the nearest
     * above the project's parent, else a new one inside the project.
     */
    private fun pinFile(ctx: Ctx, args: CreateArgs, dir: Path, cwd: Path): Path {
        val named = args.config != null || ctx.app.env.get("LINGARA_PLATFORM_TOML") != null
        val explicit = if (named) PlatformToml.locate(args.config, ctx.app.env, cwd) else null
        val found = (explicit ?: dir.parent?.let(PlatformToml::findUpward))?.takeIf { Files.isRegularFile(it) } ?: return dir.resolve(FILE)
        val toml = Rows.loadAt(ctx, found, cwd)
        if (toml.apps.any { it.name == args.name }) throw Fail.usage("${Paths.shown(found, cwd)} already pins ${args.name}")
        return found
    }

    private fun nonEmpty(dir: Path): Boolean = Files.isDirectory(dir) && Files.list(dir).use { it.findAny().isPresent }

    fun run(ctx: Ctx, args: CreateArgs) {
        val keys = keys(args)
        val cwd = Rows.cwd()
        val dir = cwd.resolve(args.dir ?: Path.of(args.name)).normalize()
        if (nonEmpty(dir)) throw Fail.message(Exit.USAGE, "dir_not_empty", "path" to Paths.shown(dir, cwd))
        val variant = if (args.bare) "bare" else "default"
        val (files, meta) = Template.render(args.lang ?: "typescript", variant, keys)
        val file = pinFile(ctx, args, dir, cwd)
        try {
            Files.createDirectories(dir)
        } catch (e: IOException) {
            throw Fail.failure("$dir: ${e.message}")
        }
        val real = Paths.real(dir)
        Template.writeAll(real, files)
        val base = Paths.real(file.parent ?: real)
        val pinned = base.resolve(file.fileName)
        Write.pinRow(pinned, NewRow(args.name, Paths.relative(real, base), keys.port, meta.start, meta.manifest), null)
        Write.ensureGitignore(base)
        register(ctx, args, pinned, cwd)
    }

    /** Step 2, last: offline when `--no-register`, or with no session to use. */
    private fun register(ctx: Ctx, args: CreateArgs, file: Path, cwd: Path) {
        if (args.noRegister) return ctx.out.nothing()
        if (ctx.ciPair() == null && ctx.app.stores.load(ctx.profile) == null) {
            ctx.out.note(Assets.message("apps_create_login_hint", "name" to args.name))
            return ctx.out.nothing()
        }
        val toml = Rows.loadAt(ctx, file, cwd)
        val row = Rows.resolve(toml, args.name, cwd)
        val manifest = Project.read(toml, row, cwd)
        Project.warn(ctx, manifest)
        Project.register(ctx, toml, row, manifest)
    }
}
