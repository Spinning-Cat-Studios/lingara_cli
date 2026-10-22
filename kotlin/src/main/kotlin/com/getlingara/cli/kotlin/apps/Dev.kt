// `lingara apps dev [name]` (ADR 16.10.26ae D7), steps 1 and 2: resolve the
// row and run every refusal offline, so a refused run sends no request; then
// register a row with no id (D6 step 4) and hand over to `DevRun`. `--reset`
// is here too: one `PATCH {render_url: null}` and nothing else.
package com.getlingara.cli.kotlin.apps

import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.auth.ciPair
import com.getlingara.cli.kotlin.core.Assets
import com.getlingara.cli.kotlin.core.Exit
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Json
import com.getlingara.cli.kotlin.core.str
import com.getlingara.cli.kotlin.project.AppRow
import com.getlingara.cli.kotlin.project.Git
import com.getlingara.cli.kotlin.project.Paths
import com.getlingara.cli.kotlin.project.PlatformToml
import com.getlingara.cli.kotlin.supervise.Cloudflared
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** `apps dev`'s flags. */
data class DevArgs(
    val name: String? = null,
    val port: Int? = null,
    val tunnel: String? = null,
    val renderUrl: String? = null,
    val takeOver: Boolean = false,
    val writeEnv: String? = null,
    val showEnv: Boolean = false,
    val reset: Boolean = false,
    val config: String? = null,
)

/** Where the dev version will point: a cloudflared it spawns ([binary]), or a fixed URL. */
data class Target(val binary: Path?, val url: String?)

/** Everything a session needs, decided before the first request. */
data class DevPlan(
    val name: String,
    val id: String,
    val dir: Path,
    val port: Int,
    val start: String,
    val renderPath: String,
    val target: Target,
    val takeOver: Boolean,
    val client: Boolean,
    val env: List<Pair<String, String>>,
    val writeEnv: Path?,
    val showEnv: Boolean,
    /** `LINGARA_TUNNEL_PROBE_URL`: where readiness is probed, never what is pointed at. */
    val probe: String?,
)

object Dev {
    fun run(ctx: Ctx, args: DevArgs) {
        val (toml, cwd) = Rows.open(ctx, args.config)
        val row = Rows.resolve(toml, args.name, cwd)
        if (args.reset) return reset(ctx, row)
        val plan = preflight(ctx, args, toml, row, cwd)
        val id = row.id ?: run {
            val app = Project.register(ctx, toml, row, Project.read(toml, row, cwd))
            // The registration printed the app; `apps dev` prints one value, at its exit.
            ctx.out.stdout.setLength(0)
            app.str("id")
        }
        DevRun.session(ctx, plan.copy(id = id))
    }

    private fun ignoredOrRefuse(path: Path, cwd: Path) {
        if (!Git.ignored(path)) throw Fail.message(Exit.USAGE, "path_not_ignored", "path" to Paths.shown(path, cwd))
    }

    private fun target(ctx: Ctx, args: DevArgs): Target = when {
        args.tunnel == "none" && args.renderUrl != null -> Target(null, args.renderUrl)
        args.tunnel == "none" -> throw Fail.usage("--tunnel none needs --render-url")
        args.renderUrl != null -> throw Fail.usage("--render-url needs --tunnel none")
        else -> Target(Cloudflared.find(ctx.app.env), null)
    }

    /** `KEY=VALUE` lines; blank lines, `#` comments and an `export ` prefix are skipped. */
    private fun envFile(path: Path): List<Pair<String, String>> {
        val text = try {
            Files.readString(path, Charsets.UTF_8)
        } catch (e: IOException) {
            throw Fail.usage("$path: ${e.message ?: e.javaClass.simpleName}")
        }
        return text.lines().mapNotNull { raw ->
            val line = raw.trim().removePrefix("export ")
            val k = line.substringBefore('=', "").trim()
            val v = line.substringAfter('=', "").trim()
            val unquoted = listOf("\"", "'").firstNotNullOfOrNull { q -> v.takeIf { it.length >= 2 && it.startsWith(q) && it.endsWith(q) }?.let { it.substring(1, it.length - 1) } }
            if ('=' in line && !line.startsWith("#") && k.isNotEmpty()) k to (unquoted ?: v) else null
        }
    }

    /** Step 2: every refusal before the network. The session record is read, never refreshed; a refresh, if due, rides the first request. */
    private fun preflight(ctx: Ctx, args: DevArgs, toml: PlatformToml, row: AppRow, cwd: Path): DevPlan {
        val start = row.start ?: throw Fail.usage("${row.name}'s row has no start command")
        val dir = toml.projectDir(row)
        val env = row.env.toList().sortedBy { it.first }.toMutableList()
        row.envFile?.let { file ->
            val path = dir.resolve(file).normalize()
            ignoredOrRefuse(path, cwd)
            env.addAll(envFile(path))
        }
        val writeEnv = args.writeEnv?.let { cwd.resolve(it).normalize() }
        writeEnv?.let { ignoredOrRefuse(it, cwd) }
        val target = target(ctx, args)
        if (ctx.ciPair() == null && ctx.app.stores.load(ctx.profile) == null) throw Fail.auth("not signed in: run `lingara login`")
        return DevPlan(
            name = row.name, id = "", dir = dir, port = args.port ?: row.port, start = start, renderPath = row.renderPath ?: "/",
            target = target, takeOver = args.takeOver, client = "client_credentials" in row.credentials, env = env,
            writeEnv = writeEnv, showEnv = args.showEnv, probe = ctx.app.env.get("LINGARA_TUNNEL_PROBE_URL"),
        )
    }

    /** `--reset`: the owner's install back on the serving version. A live session refuses it (`apps_dev_reset_held`). */
    private fun reset(ctx: Ctx, row: AppRow) {
        val id = row.id ?: throw Fail.usage("${row.name} is not registered yet: run lingara apps update ${row.name} first")
        try {
            DevApi.patchWithRetry(ctx, id) { draftVersion -> Json.obj("render_url" to null, "draft_version" to draftVersion) }
        } catch (fail: Fail) {
            throw if (fail.exit == Exit.DEV_SESSION_HELD) Fail.message(Exit.DEV_SESSION_HELD, "apps_dev_reset_held", "name" to row.name) else fail
        }
        ctx.out.note(Assets.message("apps_dev_reset", "name" to row.name))
        ctx.out.nothing()
    }
}
