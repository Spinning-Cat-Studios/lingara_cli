// The local verbs (ADR 16.10.26ae D4, D9): `apps status` reads the dev
// version; `apps pin`, `apps unpin` and `apps list --pinned` edit or read
// `platform.toml` alone (pin's one read is `app.get` for a name).
package com.getlingara.cli.kotlin.apps

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.commands.Kind
import com.getlingara.cli.kotlin.commands.Objects
import com.getlingara.cli.kotlin.core.Exit
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Json
import com.getlingara.cli.kotlin.core.str
import com.getlingara.cli.kotlin.output.Table
import com.getlingara.cli.kotlin.project.DEFAULT_PORT
import com.getlingara.cli.kotlin.project.FILE
import com.getlingara.cli.kotlin.project.NewRow
import com.getlingara.cli.kotlin.project.Paths
import com.getlingara.cli.kotlin.project.Rules
import com.getlingara.cli.kotlin.project.Write
import java.nio.file.Files
import java.nio.file.Path

object Local {
    /** `apps status`: live with its lease, parked with its last URL, or the serving version; `--json` is the `GET …/dev` body as the server answered. */
    fun status(ctx: Ctx, name: String?, config: String?) {
        val (toml, cwd) = Rows.open(ctx, config)
        val row = Rows.resolve(toml, name, cwd)
        val id = row.id ?: throw Fail.usage("${row.name} is not registered yet: run lingara apps update ${row.name}")
        val view = DevApi.get(ctx, id)
        if (ctx.out.json) ctx.out.jsonValue(view) else ctx.out.print(statusLine(view) + "\n")
    }

    /** D9's three lines. The lease time is `HH:MM` of the server's UTC stamp. */
    fun statusLine(view: JsonNode): String {
        val url = view.path("render_url").textValue() ?: return "dev: serving version"
        if (view.path("session").str("state") == "live") {
            val stamp = view.path("session").path("lease_expires_at").textValue()
            val lease = stamp?.takeIf { it.length >= 16 }?.substring(11, 16) ?: "?"
            return "dev: live $url (lease until $lease UTC)"
        }
        return "dev: parked (last $url)"
    }

    /** An id is 8-4-4-4-12 hex; anything else names a local row. */
    private fun uuidShaped(s: String): Boolean {
        val groups = s.split('-')
        return groups.map { it.length } == listOf(8, 4, 4, 4, 12) && groups.all { g -> g.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } }
    }

    /** A platform object's name as a local handle: `Luba Dive Log` → `luba-dive-log`. */
    private fun handleOf(name: String): String =
        name.map { if (it.isLetterOrDigit() && it.code < 128) it.lowercaseChar() else '-' }.joinToString("").split('-').filter { it.isNotEmpty() }.joinToString("-")

    /** The file pin writes: the one `open` would find, else a new one here; and the names it already pins. */
    private fun pinTarget(ctx: Ctx, config: String?, cwd: Path): Pair<Path, List<String>> {
        val found = Rows.locate(ctx, config, cwd)
        if (found != null && Files.isRegularFile(found)) return found to Rows.loadAt(ctx, found, cwd).apps.map { it.name }
        return (found ?: cwd.resolve(FILE)) to emptyList()
    }

    /** `apps pin <id|name> [--path <dir>]`: a row in platform.toml and, when it has none of that name, in the example. Never a platform write. */
    fun pin(ctx: Ctx, app: String, dir: String?, config: String?) {
        val cwd = Rows.cwd()
        val (name, id) = if (uuidShaped(app)) {
            handleOf(Objects.get(ctx, Kind.APP, app).str("name")) to app
        } else {
            if (dir == null) throw Fail.usage("pinning $app by name needs --path <dir>")
            app to null
        }
        if (!Rules.appNameOk(name)) throw Fail.message(Exit.USAGE, "app_name_invalid", "name" to name)
        val (file, names) = pinTarget(ctx, config, cwd)
        if (name in names) throw Fail.usage("${Paths.shown(file, cwd)} already pins $name")
        val base = file.parent ?: cwd
        val project = Paths.real(cwd.resolve(dir ?: ".").normalize())
        Write.pinRow(file, NewRow(name, Paths.relative(project, Paths.real(base)), DEFAULT_PORT), id)
        ctx.out.nothing()
    }

    /** `apps unpin <name>`: platform.toml only; the shared example changes by review. */
    fun unpin(ctx: Ctx, name: String, config: String?) {
        val (toml, cwd) = Rows.open(ctx, config)
        if (!Write.removeRow(toml.file, name)) throw Fail.message(Exit.USAGE, "app_row_unknown", "file" to toml.shown(cwd), "value" to name)
        ctx.out.nothing()
    }

    /** `apps list --pinned`: the rows, as JSON or as a NAME/ID/PATH/PORT table. */
    fun listPinned(ctx: Ctx, config: String?) {
        val (toml, _) = Rows.open(ctx, config)
        val rows = toml.apps.map { Json.obj("name" to it.name, "id" to it.id, "path" to it.path, "port" to it.port) }
        if (ctx.out.json) return ctx.out.jsonValue(Json.of(rows))
        val cells = rows.map { r -> listOf("name", "id", "path", "port").map { Table.cell(r.path(it)) } }
        ctx.out.print(Table.render(listOf("NAME", "ID", "PATH", "PORT"), cells))
    }
}
