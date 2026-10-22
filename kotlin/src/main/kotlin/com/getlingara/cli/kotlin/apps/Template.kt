// Rendering a template (ADR 16.10.26ae D2, D3; spec/templates.md): the fixed
// `{{key}}` set, the array syntax each language's bare template uses, the two
// renaming rules, and `template.toml`, read and never written.
package com.getlingara.cli.kotlin.apps

import com.getlingara.cli.kotlin.core.Assets
import com.getlingara.cli.kotlin.core.Fail
import org.tomlj.Toml
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** What the scaffold substitutes, each derived once (spec/templates.md). */
data class Keys(val name: String, val displayName: String, val description: String, val port: Int, val slots: List<String>, val context: List<String>)

/** `template.toml`: the row's start command and its manifest source, `("manifest" | "manifest_command", value)`. */
data class Meta(val start: String? = null, val manifest: Pair<String, String>? = null)

/** One file, at its path in the project. */
class Rendered(val path: String, val bytes: ByteArray)

object Template {
    private const val META = "template.toml"

    /** `["a", "b"]` in the syntax the template's language writes an array in. */
    fun array(lang: String, variant: String, items: List<String>): String {
        val inner = items.joinToString(", ") { "\"$it\"" }
        if (variant != "bare") return "[$inner]"
        return when (lang) {
            "go" -> "[]string{$inner}"
            "java" -> "List.of($inner)"
            "kotlin" -> "listOf($inner)"
            else -> "[$inner]"
        }
    }

    /** A segment starting `_` is written with `.`; a `.tmpl` suffix is dropped. */
    fun projectPath(relative: String): String {
        val segments = relative.split('/').map { if (it.startsWith("_")) "." + it.drop(1) else it }
        return segments.joinToString("/").removeSuffix(".tmpl")
    }

    fun substitute(text: String, lang: String, variant: String, keys: Keys): String = listOf(
        "{{name}}" to keys.name,
        "{{display_name}}" to keys.displayName,
        "{{description}}" to keys.description,
        "{{port}}" to keys.port.toString(),
        "{{slots}}" to array(lang, variant, keys.slots),
        "{{context}}" to array(lang, variant, keys.context),
    ).fold(text) { t, (k, v) -> t.replace(k, v) }

    /** Every file of `<lang>/<variant>`, rendered, and its `template.toml`. */
    fun render(lang: String, variant: String, keys: Keys): Pair<List<Rendered>, Meta> {
        val files = Assets.template(lang, variant)
        if (files.isEmpty()) throw Fail.failure("this lingara carries no $lang $variant template")
        val meta = files.firstOrNull { it.first == META }?.let { parseMeta(String(it.second, Charsets.UTF_8)) } ?: Meta()
        val out = files.filter { it.first != META }.map { (relative, bytes) ->
            Rendered(projectPath(relative), substitute(String(bytes, Charsets.UTF_8), lang, variant, keys).toByteArray(Charsets.UTF_8))
        }
        return out to meta
    }

    private fun parseMeta(text: String): Meta {
        val value = Toml.parse(text)
        value.errors().firstOrNull()?.let { throw Fail.failure("$META: $it") }
        val manifest = value.getString("manifest")?.let { "manifest" to it }
            ?: value.getString("manifest_command")?.let { "manifest_command" to it }
        return Meta(value.getString("start"), manifest)
    }

    /** Writes every file under [dir], creating directories as it goes. */
    fun writeAll(dir: Path, files: List<Rendered>) {
        for (file in files) {
            val path = dir.resolve(file.path)
            try {
                Files.createDirectories(path.parent)
                Files.write(path, file.bytes)
            } catch (e: IOException) {
                throw Fail.failure("$path: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
}
