// What the CLI checks in a wire manifest before it sends one (ADR 16.10.26ae
// D6): the ephemeral render URL a version may never carry, the manifest
// rules a warning depends on, and the warnings themselves, pinned by
// `spec/manifest-warnings.json` (the app kits' `manifestWarnings`). The
// server stays the authority on every rule; these run first so a refusal
// costs no request.
package com.getlingara.cli.kotlin.apps

import com.fasterxml.jackson.databind.JsonNode

object ManifestRules {
    /** The slots a manifest may name (the app view's `AppSlotName`). */
    private val SLOTS = setOf("home.side", "plans.empty_detail")

    /** The text each warning prints with, as `manifest_warning`'s `{text}`. */
    private val WARNING_TEXTS = mapOf("single_locale" to "the listing is in its default locale only; give name and description a second locale")

    /** The URL's host, lowercased: no scheme, user, port or path. */
    private fun host(url: String): String {
        val rest = url.substringAfter("://", url)
        val authority = rest.split('/', '?', '#').first()
        return authority.substringAfterLast('@').substringBefore(':').lowercase()
    }

    /** A quick tunnel's or the template's placeholder: never a version's URL. */
    fun ephemeral(renderUrl: String): Boolean {
        val host = host(renderUrl)
        return host == "dev.invalid" || host == "trycloudflare.com" || host.endsWith(".trycloudflare.com")
    }

    private fun localeMap(v: JsonNode) = v.isObject && !v.isEmpty && v.all { it.isTextual && it.textValue().isNotEmpty() }

    private fun hasDuplicate(v: JsonNode): Boolean {
        val items = if (v.isArray) v.toList() else emptyList()
        return items.withIndex().any { (i, a) -> items.subList(0, i).contains(a) }
    }

    private fun renderUrlOk(v: JsonNode): Boolean {
        val u = v.textValue() ?: return false
        return u.length > 8 && u.substring(0, 8).equals("https://", ignoreCase = true) && host(u).isNotEmpty()
    }

    /** The first rule [m] breaks, of the ones a warning depends on, in the app kits' order; `null` when it keeps them all. */
    fun brokenRule(m: JsonNode): String? {
        val locale = m.path("default_locale").textValue().orEmpty()
        val inMap = { key: String -> !m.path(key).isObject || m.path(key).has(locale) }
        val slots = m.path("slots")
        val context = m.get("context")
        val checks = listOf(
            "manifest_version" to (m.path("manifest_version").let { it.isIntegralNumber && it.asLong() == 1L }),
            "default_locale" to (locale.isNotEmpty() && inMap("name") && inMap("description")),
            "name" to localeMap(m.path("name")),
            "description" to localeMap(m.path("description")),
            "render_url" to renderUrlOk(m.path("render_url")),
            "slots" to (slots.isArray && !slots.isEmpty && slots.all { it.textValue() in SLOTS }),
            "context" to (context == null || context.isArray && context.all { it.isTextual }),
            "locales" to (m.get("locales") == null),
            "duplicate" to listOf("slots", "context", "scopes").none { hasDuplicate(m.path(it)) },
        )
        return checks.firstOrNull { !it.second }?.first
    }

    /**
     * Where `name` and `description` both have an entry, and
     * `long_description` too when present and not null, in byte order; empty
     * for a manifest the rules refuse.
     */
    fun locales(m: JsonNode): List<String> {
        if (brokenRule(m) != null) return emptyList()
        val keys = { key: String -> m.path(key).fieldNames().asSequence().toList() }
        val long = m.path("long_description")
        val covered = { l: String -> l in keys("description") && (long.isNull || long.isMissingNode || l in keys("long_description")) }
        return keys("name").filter(covered).sortedWith(byteOrder)
    }

    /** Byte order of the UTF-8 forms, as Rust's `String` sorts. */
    private val byteOrder = Comparator<String> { a, b ->
        val x = a.toByteArray()
        val y = b.toByteArray()
        (0 until minOf(x.size, y.size)).firstOrNull { x[it] != y[it] }?.let { (x[it].toInt() and 0xff) - (y[it].toInt() and 0xff) } ?: (x.size - y.size)
    }

    /** The listing's warnings: v1 `single_locale` alone. Never a refusal. */
    fun warnings(m: JsonNode): List<String> {
        val locales = locales(m)
        val onlyDefault = locales.size == 1 && m.path("default_locale").textValue() == locales[0]
        return if (onlyDefault) listOf("single_locale") else emptyList()
    }

    fun warningText(code: String): String = WARNING_TEXTS[code].orEmpty()
}
