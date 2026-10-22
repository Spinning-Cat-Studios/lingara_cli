// `<config dir>/credentials.json`, mode `0600` in a `0700` directory, shaped
// `{"schema": 1, "profiles": {"<profile>": <record>}}` (16.10.26ad D4). The
// file is written in the reference kit's pretty form (two-space indent,
// `"key": value`, profiles sorted), so a file one kit wrote reads the same in
// a diff as another's.
package com.getlingara.cli.kotlin.store

import com.getlingara.cli.kotlin.core.Json
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class FileStore(dir: Path) : CredentialStore {
    val path: Path = dir.resolve("credentials.json")

    private fun read(): MutableMap<String, ProfileRecord> {
        val text = try {
            Files.readString(path, Charsets.UTF_8)
        } catch (_: NoSuchFileException) {
            return sortedMapOf()
        } catch (e: IOException) {
            throw StoreBroken("$path: ${e.message}")
        }
        val root = Json.parse(text)?.takeIf { it.isObject } ?: throw StoreBroken("$path: not a credentials file")
        return root.path("profiles").properties().associateTo(sortedMapOf()) { (name, v) -> name to ProfileRecord.read(v) }
    }

    private fun write(profiles: Map<String, ProfileRecord>) {
        try {
            Files.createDirectories(path.parent)
            restrict(path.parent, "rwx------")
            Files.writeString(path, render(profiles), Charsets.UTF_8)
            restrict(path, "rw-------")
        } catch (e: IOException) {
            throw StoreBroken("$path: ${e.message}")
        }
    }

    fun has(profile: String): Boolean = runCatching { profile in read() }.getOrDefault(false)

    override fun load(profile: String): ProfileRecord? = read()[profile]

    override fun save(profile: String, record: ProfileRecord) {
        write(read().also { it[profile] = record })
    }

    /** Removes the profile; the file goes when it held only that one. */
    override fun delete(profile: String) {
        val profiles = read()
        if (profiles.remove(profile) == null) return
        if (profiles.isEmpty()) Files.deleteIfExists(path) else write(profiles)
    }

    companion object {
        /** POSIX modes where the file system has them; elsewhere (Windows) the directory's ACL stands. */
        private fun restrict(path: Path, mode: String) {
            runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(mode)) }
        }

        private fun quote(s: String): String = Json.write(Json.of(s))

        fun render(profiles: Map<String, ProfileRecord>): String {
            if (profiles.isEmpty()) return "{\n  \"schema\": 1,\n  \"profiles\": {}\n}\n"
            val blocks = profiles.entries.joinToString(",\n") { (name, r) ->
                val body = r.toJson().properties().joinToString(",\n") { (k, v) -> "      ${quote(k)}: ${Json.write(v)}" }
                "    ${quote(name)}: {\n$body\n    }"
            }
            return "{\n  \"schema\": 1,\n  \"profiles\": {\n$blocks\n  }\n}\n"
        }
    }
}
