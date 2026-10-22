// A project's manifest and its registration (ADR 16.10.26ae D1 step 2, D6
// steps 1, 4 and 5): read from `lingara/connection.js` by the literal
// reader, or from `manifest_command`'s stdout; checked; its warnings
// printed; and, for a row with no id, registered as a private app.
package com.getlingara.cli.kotlin.apps

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.commands.Creation
import com.getlingara.cli.kotlin.commands.Kind
import com.getlingara.cli.kotlin.core.Assets
import com.getlingara.cli.kotlin.core.Exit
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Json
import com.getlingara.cli.kotlin.core.str
import com.getlingara.cli.kotlin.literal.ConnectionLiteral
import com.getlingara.cli.kotlin.literal.Refusal
import com.getlingara.cli.kotlin.project.AppRow
import com.getlingara.cli.kotlin.project.Paths
import com.getlingara.cli.kotlin.project.PlatformToml
import com.getlingara.cli.kotlin.project.Write
import com.getlingara.cli.kotlin.supervise.Supervise
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

object Project {
    private const val DEFAULT_MANIFEST = "lingara/connection.js"

    private fun file(toml: PlatformToml, row: AppRow): Path = toml.projectDir(row).resolve(row.manifest ?: DEFAULT_MANIFEST).normalize()

    /** The manifest's source as messages name it, relative to [cwd]. */
    fun source(toml: PlatformToml, row: AppRow, cwd: Path): String = row.manifestCommand ?: Paths.shown(file(toml, row), cwd)

    /** The wire manifest the project describes, checked before any request. */
    fun read(toml: PlatformToml, row: AppRow, cwd: Path): JsonNode {
        val shown = source(toml, row, cwd)
        val manifest = row.manifestCommand?.let { runCommand(it, toml.projectDir(row)) } ?: literal(file(toml, row), shown)
        ManifestRules.brokenRule(manifest)?.let { rule ->
            throw Fail(Exit.USAGE, "manifest_invalid", "$shown: the manifest breaks the $rule rule")
        }
        return manifest
    }

    private fun literal(file: Path, shown: String): JsonNode {
        val text = try {
            Files.readString(file, Charsets.UTF_8)
        } catch (e: IOException) {
            throw Fail.usage("$shown: ${e.message ?: e.javaClass.simpleName}")
        }
        return try {
            ConnectionLiteral.appManifest(text)
        } catch (r: Refusal) {
            throw r.fail(shown)
        }
    }

    /** D3: `manifest_command` through the platform shell, in the project; its stdout is the wire manifest, its stderr the developer's to see. */
    private fun runCommand(command: String, dir: Path): JsonNode {
        val builder = Supervise.shell(command).redirectInput(Supervise.NULL_INPUT).redirectError(ProcessBuilder.Redirect.INHERIT)
        val process = try {
            builder.directory(dir.toFile()).start()
        } catch (e: IOException) {
            throw Fail.failure("$command: ${e.message}")
        }
        val stdout = process.inputStream.readBytes()
        val status = process.waitFor()
        if (status != 0) throw Fail.failure("$command exited $status; fix it, then run lingara apps update")
        return Json.parse(stdout) ?: throw Fail.usage("$command printed no manifest JSON")
    }

    /** D6 step 5: one `manifest_warning` line each, never a failure. */
    fun warn(ctx: Ctx, manifest: JsonNode): List<String> = ManifestRules.warnings(manifest).onEach { code ->
        ctx.out.note(Assets.message("manifest_warning", "code" to code, "text" to ManifestRules.warningText(code)))
    }

    /** The display name D2 derives: each `-` a space, each word capitalised. */
    fun displayName(name: String): String = name.split('-').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } }

    /**
     * D1 step 2 / D6 step 4: the private OAuth client, then the app under it
     * through the registration form's own steps, the id written into the row.
     * Prints the app as the registration form does.
     */
    fun register(ctx: Ctx, toml: PlatformToml, row: AppRow, manifest: JsonNode): JsonNode {
        val title = displayName(row.name)
        val client = Json.obj("client_type" to "confidential", "allowed_scopes" to emptyList<String>(), "billing_mode" to "allowance", "redirect_uris" to emptyList<String>())
        val made = Creation(ctx)
        val app = made.conclude {
            val clientId = made.obj(Kind.OAUTH_CLIENT, title, null, client).str("id")
            Kind.APP to made.obj(Kind.APP, title, clientId, Json.obj("manifest" to manifest)).str("id")
        }
        Write.setId(toml.file, row.name, app.str("id"))
        return app
    }
}
