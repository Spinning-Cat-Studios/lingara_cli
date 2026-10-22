// Every shared file this kit reads, from its own classpath copy under
// `lingara/` (ADR 16.10.26af D8): `endpoints.json` (where production is),
// `messages.en.json` (what a prompt says, byte for byte), `tools.toml`
// (cloudflared's floor), `platform-toml.schema.json` and the app templates.
// All are committed copies held equal to their sources by `make
// check-kit-assets`, because a published jar carries only its own directory.
// The version and the templates' index are the build's (`lingara-build/`).
package com.getlingara.cli.kotlin.core

import org.tomlj.Toml

/** `endpoints.json`: the first-party client and production's two bases. */
data class Endpoints(val clientId: String, val apiUrl: String, val platformUrl: String)

object Assets {
    private const val ROOT = "/lingara/"

    /** The bytes of a classpath resource, or `null`. */
    fun bytes(path: String): ByteArray? = Assets::class.java.getResourceAsStream(path)?.use { it.readBytes() }

    fun text(name: String): String = bytes(ROOT + name)?.toString(Charsets.UTF_8) ?: ""

    /** `lingara <VERSION> (kotlin)`'s version, written at build time. */
    val version: String by lazy { bytes("/lingara-build/version.txt")?.toString(Charsets.UTF_8)?.trim() ?: "0.0.0" }

    private val texts: Map<String, String> by lazy {
        Json.parse(text("messages.en.json"))?.path("messages")?.associate { it.str("id") to it.str("text") } ?: emptyMap()
    }

    /** The message `id` with each `{name}` filled; an id the file lacks prints as itself. */
    fun message(id: String, vararg vars: Pair<String, String>): String =
        vars.fold(texts[id] ?: id) { t, (k, v) -> t.replace("{$k}", v) }

    fun hasMessage(id: String): Boolean = id in texts

    val endpoints: Endpoints by lazy {
        val e = Json.parse(text("endpoints.json"))
        Endpoints(
            clientId = e?.strOrNull("client_id") ?: "lingara-cli",
            apiUrl = e?.strOrNull("api_url") ?: "https://api.getlingara.com",
            platformUrl = e?.strOrNull("platform_url") ?: "https://platform.getlingara.com",
        )
    }

    /** `tools.toml`'s `[cloudflared] min_version`, as dotted numbers. */
    fun cloudflaredMinVersion(): List<Long> {
        val text = Toml.parse(text("tools.toml")).getString("cloudflared.min_version") ?: ""
        return text.split('.').mapNotNull { it.toLongOrNull() }
    }

    private val templateIndex: List<String> by lazy {
        bytes("/lingara-build/templates.txt")?.toString(Charsets.UTF_8)?.lines()?.filter { it.isNotEmpty() } ?: emptyList()
    }

    /** Every file of one template (`<lang>/<variant>/`), path relative to it. */
    fun template(lang: String, variant: String): List<Pair<String, ByteArray>> {
        val prefix = "apps/$lang/$variant/"
        return templateIndex.filter { it.startsWith(prefix) }.mapNotNull { path ->
            bytes("${ROOT}templates/$path")?.let { path.removePrefix(prefix) to it }
        }
    }
}
