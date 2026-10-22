// `platform.toml` (ADR 16.10.26ae D4): the gitignored, per-developer file
// naming the apps a checkout works on. Found by `--config`, then
// `LINGARA_PLATFORM_TOML`, then by walking up from the current directory as
// Cargo does. Read in a fixed order, so the first refusal is the same in
// every kit: TOML syntax (`platform_toml_schema`), a secret-shaped value or
// key (`platform_toml_secret`, `Secrets`), then the rules
// `spec/platform-toml.schema.json` writes (`platform_toml_schema`, `Rules`).
// A tracked file is only a warning (`Git`).
package com.getlingara.cli.kotlin.project

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.core.Env
import com.getlingara.cli.kotlin.core.Exit
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.items
import com.getlingara.cli.kotlin.core.strOrNull
import com.getlingara.cli.kotlin.core.strings
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

const val FILE = "platform.toml"
const val EXAMPLE = "platform.toml.example"

/** The example's id, which every kit reads as no id (spec/templates.md). */
const val PLACEHOLDER_ID = "00000000-0000-0000-0000-000000000000"
const val DEFAULT_PORT = 8787

/** One `[[apps]]` row. */
data class AppRow(
    val name: String,
    val id: String? = null,
    val path: String = ".",
    val port: Int = DEFAULT_PORT,
    val start: String? = null,
    val renderPath: String? = null,
    val manifest: String? = null,
    val manifestCommand: String? = null,
    val credentials: List<String> = emptyList(),
    val envFile: String? = null,
    val env: Map<String, String> = emptyMap(),
)

class PlatformToml(val file: Path, val api: String?, val profile: String?, val apps: List<AppRow>) {
    /** The directory the file is in, which every row's `path` is relative to. */
    val dir: Path get() = file.parent ?: Path.of(".")

    fun projectDir(row: AppRow): Path = dir.resolve(row.path).normalize()

    /** The file's name as messages print it: relative to [cwd] where it can be. */
    fun shown(cwd: Path): String = Paths.shown(file, cwd)

    companion object {
        /** `--config`, then `LINGARA_PLATFORM_TOML`, then the nearest `platform.toml` at or above [cwd]. */
        fun locate(config: String?, env: Env, cwd: Path): Path? {
            val explicit = config ?: env.get("LINGARA_PLATFORM_TOML") ?: return findUpward(cwd)
            return cwd.resolve(explicit)
        }

        /** The nearest `platform.toml` in [from] or a directory above it. */
        fun findUpward(from: Path): Path? = generateSequence(from) { it.parent }.map { it.resolve(FILE) }.firstOrNull { Files.isRegularFile(it) }

        fun schemaFail(file: String, reason: String) = Fail.message(Exit.PLATFORM_TOML_SCHEMA, "platform_toml_schema", "file" to file, "reason" to reason)

        /** Reads and checks [path]; [shown] is how messages name it. */
        fun load(path: Path, shown: String): PlatformToml {
            val text = try {
                Files.readString(path, Charsets.UTF_8)
            } catch (e: IOException) {
                throw schemaFail(shown, e.message ?: e.javaClass.simpleName)
            }
            val value = TomlJson.parse(text) { reason -> schemaFail(shown, reason) }
            Secrets.firstSecret(value)?.let { key ->
                throw Fail.message(Exit.PLATFORM_TOML_SECRET, "platform_toml_secret", "file" to shown, "key" to key)
            }
            Rules.check(value)?.let { throw schemaFail(shown, it) }
            return read(path, value)
        }

        private fun read(path: Path, value: JsonNode): PlatformToml {
            val platform = value.path("platform")
            return PlatformToml(path, platform.strOrNull("api"), platform.strOrNull("profile"), value.items("apps").map(::row))
        }

        private fun row(v: JsonNode) = AppRow(
            name = v.strOrNull("name").orEmpty(),
            id = v.strOrNull("id")?.takeIf { it != PLACEHOLDER_ID },
            path = v.strOrNull("path") ?: ".",
            port = v.path("port").takeIf { it.isIntegralNumber && it.asLong() in 0..65535 }?.asInt() ?: DEFAULT_PORT,
            start = v.strOrNull("start"),
            renderPath = v.strOrNull("render_path"),
            manifest = v.strOrNull("manifest"),
            manifestCommand = v.strOrNull("manifest_command"),
            credentials = v.path("credentials").strings(),
            envFile = v.strOrNull("env_file"),
            env = v.path("env").properties().mapNotNull { (k, s) -> s.textValue()?.let { k to it } }.toMap(),
        )
    }
}

/** Paths as messages show them. */
object Paths {
    /** [path] relative to [cwd] where it is inside it, else as is. */
    fun shown(path: Path, cwd: Path): String = if (path.startsWith(cwd) && path != cwd) cwd.relativize(path).toString() else path.toString()

    /** [dir] relative to [base], `/`-separated; `.` when they are one. */
    fun relative(dir: Path, base: Path): String = when {
        dir == base -> "."
        dir.startsWith(base) -> base.relativize(dir).joinToString("/")
        else -> dir.toString()
    }

    /** The real path where it exists, through any symlink, so two spellings of one directory compare. */
    fun real(path: Path): Path = runCatching { path.toRealPath() }.getOrDefault(path)
}
