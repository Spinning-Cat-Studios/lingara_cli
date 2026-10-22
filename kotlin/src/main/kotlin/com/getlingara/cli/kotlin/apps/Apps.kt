// `lingara apps …` (ADR 16.10.26ad D1, D2; 16.10.26ae). `create` and
// `update` each have **one dispatch point** on whether `--manifest` is
// present: with it, 16.10.26ad's registration path, untouched; without it,
// 16.10.26ae's scaffolding (`Scaffold`) and project-reading
// (`ProjectUpdate`) paths.
package com.getlingara.cli.kotlin.apps

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.auth.call
import com.getlingara.cli.kotlin.commands.Columns
import com.getlingara.cli.kotlin.commands.Creation
import com.getlingara.cli.kotlin.commands.Kind
import com.getlingara.cli.kotlin.commands.Objects
import com.getlingara.cli.kotlin.core.Exit
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Json
import com.getlingara.cli.kotlin.core.str
import com.getlingara.cli.kotlin.http.Body
import com.getlingara.cli.kotlin.http.Op
import com.getlingara.cli.kotlin.http.Request
import com.getlingara.cli.kotlin.http.Routes
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** `apps create`'s flags, both forms; the scaffold's defaults are its own. */
data class CreateArgs(
    val name: String,
    val client: String? = null,
    val manifest: Path? = null,
    val lang: String? = null,
    val bare: Boolean = false,
    val dir: Path? = null,
    val noRegister: Boolean = false,
    val description: String? = null,
    val port: Int? = null,
    val slot: List<String> = emptyList(),
    val context: List<String> = emptyList(),
    val config: String? = null,
)

/** `apps update`'s flags: an id with `--manifest`, or the project form. */
data class UpdateArgs(val id: String?, val manifest: Path?, val version: String?, val config: String?)

object Apps {
    val COLUMNS: Columns = listOf("ID" to "id", "NAME" to "name", "CLIENT" to "parent_id", "SERVING" to "serving_version", "VISIBILITY" to "visibility")
    private val VERSION_COLUMNS: Columns = listOf("SEMVER" to "semver", "STATE" to "state", "STATE_AT" to "state_at", "REVIEWED" to "review_basis")

    /** The one rule `conflicts` cannot say: `--manifest` and `--client` come together (the registration form) or not at all (the scaffold). */
    fun createEither(ctx: Ctx, args: CreateArgs) {
        when {
            args.manifest != null && args.client != null -> create(ctx, args.name, args.client, args.manifest)
            args.manifest == null && args.client == null -> Scaffold.run(ctx, args)
            else -> throw Fail.usage("--manifest and --client go together: both register an app from a file, neither scaffolds a project")
        }
    }

    /** `--manifest <path>`: the file's JSON. */
    fun manifestFile(path: Path): JsonNode {
        val text = try {
            Files.readString(path, Charsets.UTF_8)
        } catch (e: IOException) {
            throw Fail.usage("$path: ${e.message ?: e.javaClass.simpleName}")
        }
        return Json.parse(text) ?: throw Fail.usage("$path is not JSON")
    }

    /** The registration form: register the object, write no files. */
    fun create(ctx: Ctx, name: String, client: String, manifest: Path): JsonNode {
        val body = Json.obj("manifest" to manifestFile(manifest))
        val made = Creation(ctx)
        return made.conclude { Kind.APP to made.obj(Kind.APP, name, client, body).str("id") }
    }

    fun update(ctx: Ctx, args: UpdateArgs) = if (args.manifest != null) updateFromFile(ctx, args.id, args.manifest) else ProjectUpdate.run(ctx, args)

    /**
     * `apps update <id> --manifest <path>`: a new version from the file. A
     * quick-tunnel or `dev.invalid` render URL is refused before any request
     * (16.10.26ad D2 with 16.10.26ae D6 step 3's guard).
     */
    private fun updateFromFile(ctx: Ctx, id: String?, path: Path) {
        id ?: throw Fail.usage("apps update --manifest needs the app's id")
        val manifest = manifestFile(path)
        if (ManifestRules.ephemeral(manifest.str("render_url"))) {
            throw Fail.message(Exit.RENDER_URL_EPHEMERAL, "render_url_ephemeral", "file" to path.toString())
        }
        ctx.out.value(Objects.bodyUpdate(ctx, Kind.APP, id) { it.set<JsonNode>("manifest", manifest) })
    }

    /** `{"visibility": "public"}`: review itself is the operator's, in the portal. */
    fun submit(ctx: Ctx, id: String, version: String) {
        val route = Routes.objectRoute(Kind.APP.word, Op.SUBMIT)
        ctx.out.value(ctx.call(Request(route, listOf("id" to id, "semver" to version), body = Body.JsonBody(Json.obj("visibility" to "public")))))
    }

    fun versionOp(ctx: Ctx, op: Op, id: String, semver: String) = ctx.out.value(Objects.versionOp(ctx, Kind.APP, op, id, semver))

    /** Every page, newest first; `next_cursor` is the last semver seen. */
    fun listVersions(ctx: Ctx, id: String) =
        Objects.printRows(ctx, Objects.pages(ctx, Routes.objectRoute(Kind.APP.word, Op.VERSIONS), listOf("id" to id)), VERSION_COLUMNS)
}
