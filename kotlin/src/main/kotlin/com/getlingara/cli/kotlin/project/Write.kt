// Writing `platform.toml`, its example and `.gitignore`, byte for byte as
// `spec/templates.md` says, so every kit leaves the same files behind. The
// edits are line-based on purpose: a developer's comments and layout outside
// the touched row survive.
package com.getlingara.cli.kotlin.project

import com.getlingara.cli.kotlin.core.Fail
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** A row as `create` and `pin` write it; [manifest] is `("manifest" | "manifest_command", value)`. */
data class NewRow(val name: String, val path: String, val port: Int, val start: String? = null, val manifest: Pair<String, String>? = null)

object Write {
    const val FILE_HEADER = "# platform.toml — gitignored; copy from platform.toml.example.\n" +
        "# Never put a secret here. lingara refuses to read a value that looks like one.\n" +
        "schema = 1\n"

    const val EXAMPLE_HEADER = "# platform.toml.example — checked in. Copy it to platform.toml (gitignored),\n" +
        "# then run lingara apps update to register your own private copy of each app.\n" +
        "schema = 1\n"

    /** A TOML basic string. */
    fun quote(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun rowText(row: NewRow, id: String?): String = buildString {
        append("\n[[apps]]\nname = ${quote(row.name)}\n")
        id?.let { append("id = ${quote(it)}\n") }
        append("path = ${quote(row.path)}\nport = ${row.port}\n")
        row.start?.let { append("start = ${quote(it)}\n") }
        row.manifest?.let { (key, value) -> append("$key = ${quote(value)}\n") }
    }

    private fun io(path: Path, e: IOException) = Fail.failure("$path: ${e.message ?: e.javaClass.simpleName}")

    private fun readOr(path: Path, header: String): String = try {
        Files.readString(path, Charsets.UTF_8).let { if (it.isNotEmpty() && !it.endsWith("\n")) it + "\n" else it }
    } catch (_: NoSuchFileException) {
        header
    } catch (e: IOException) {
        throw io(path, e)
    }

    private fun write(path: Path, text: String) {
        try {
            Files.writeString(path, text, Charsets.UTF_8)
        } catch (e: IOException) {
            throw io(path, e)
        }
    }

    private fun read(path: Path): String = try {
        Files.readString(path, Charsets.UTF_8)
    } catch (e: IOException) {
        throw io(path, e)
    }

    /** Whether [text] holds a row named [name]. */
    fun hasRow(text: String, name: String): Boolean = spanOf(text, name) != null

    /** Appends the row to [file] (with [id], if any) and, when the example beside it has no row of that name, to the example (with the placeholder). */
    fun pinRow(file: Path, row: NewRow, id: String?) {
        write(file, readOr(file, FILE_HEADER) + rowText(row, id))
        val example = file.resolveSibling(EXAMPLE)
        val current = readOr(example, EXAMPLE_HEADER)
        if (!hasRow(current, row.name)) write(example, current + rowText(row, PLACEHOLDER_ID))
    }

    /** `lines()` as Rust's `str::lines` splits: no last empty line after a final newline. */
    private fun lines(text: String): List<String> = text.split("\n").let { if (text.endsWith("\n")) it.dropLast(1) else it }.map { it.removeSuffix("\r") }

    /** Each `[[apps]]` row's line span `[start, end)`: up to the next table header that is not its own `[apps.env]`. */
    private fun rows(lines: List<String>): List<IntRange> {
        val header = { l: String -> l.trimStart().startsWith("[") && l.trim() != "[apps.env]" }
        return lines.indices.filter { lines[it].trim() == "[[apps]]" }.map { s ->
            val end = (s + 1 until lines.size).firstOrNull { header(lines[it]) } ?: lines.size
            s until end
        }
    }

    private fun spanOf(text: String, name: String): IntRange? {
        val all = lines(text)
        val line = "name = ${quote(name)}"
        return rows(all).firstOrNull { span -> span.any { all[it].trim() == line } }
    }

    private fun joined(lines: List<String>) = lines.joinToString("") { "$it\n" }

    /** Writes [id] into the row named [name]: replaces its `id` line, or adds one directly after its `name` line. */
    fun setId(file: Path, name: String, id: String) {
        val text = read(file)
        val span = spanOf(text, name) ?: return
        val lines = lines(text).toMutableList()
        val idLine = "id = ${quote(id)}"
        val inRow = { l: String, key: String -> l.trimStart().startsWith(key) }
        val existing = span.firstOrNull { inRow(lines[it], "id ") || inRow(lines[it], "id=") }
        if (existing != null) {
            lines[existing] = idLine
        } else {
            lines.add((span.firstOrNull { inRow(lines[it], "name") } ?: span.first) + 1, idLine)
        }
        write(file, joined(lines))
    }

    /** Removes the row named [name]; `false` when there is none. */
    fun removeRow(file: Path, name: String): Boolean {
        val text = read(file)
        val span = spanOf(text, name) ?: return false
        val lines = lines(text)
        var start = span.first
        if (span.last + 1 == lines.size) {
            while (start > 0 && lines[start - 1].isBlank()) start--
        }
        write(file, joined(lines.subList(0, start) + lines.subList(span.last + 1, lines.size)))
        return true
    }

    /** Adds a `platform.toml` line to `<dir>/.gitignore` when no line is one. */
    fun ensureGitignore(dir: Path) {
        val path = dir.resolve(".gitignore")
        val text = readOr(path, "")
        if (lines(text).any { it.trim() == FILE }) return
        write(path, "$text$FILE\n")
    }
}
