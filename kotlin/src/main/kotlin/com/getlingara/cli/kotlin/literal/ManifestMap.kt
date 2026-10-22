// The closed key map from `lingara.v1`'s `ManifestOptions` to the wire
// manifest (spec/connection-literal.md), in `lingara.manifest`'s member
// order. A key the map does not name is refused, never passed through.
package com.getlingara.cli.kotlin.literal

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.core.Json

object ManifestMap {
    /** Literal key → wire key, in the order the wire manifest writes them. */
    private val KEYS = listOf(
        "defaultLocale" to "default_locale",
        "name" to "name",
        "description" to "description",
        "renderUrl" to "render_url",
        "slots" to "slots",
        "context" to "context",
        "scopes" to "scopes",
        "tutorNote" to "tutor_note",
        "longDescription" to "long_description",
        "developer" to "developer",
        "images" to "images",
    )
    private val REQUIRED = listOf("name", "description", "renderUrl", "slots")
    private val DEVELOPER = listOf("name" to "name", "url" to "url", "supportUrl" to "support_url")
    private val IMAGE = listOf("sha256" to "sha256", "role" to "role", "alt" to "alt")

    private fun find(members: List<Member>, key: String): Lit? = members.firstOrNull { it.key == key }?.value

    /** A string is the default locale's; an object passes its tags through. */
    private fun localeMap(lit: Lit, locale: String): JsonNode = if (lit is Lit.Str) Json.obj(locale to lit.s) else lit.toJson()

    /** An object whose keys must all be in [map], renamed through it. */
    private fun renamed(lit: Lit, map: List<Pair<String, String>>, locale: String): JsonNode {
        if (lit !is Lit.Obj) return lit.toJson()
        lit.members.firstOrNull { m -> map.none { it.first == m.key } }?.let { throw Refusal.At("manifest_key", it.at) }
        val out = Json.obj()
        for ((from, to) in map) {
            val value = find(lit.members, from) ?: continue
            out.set<JsonNode>(to, if (from == "alt") localeMap(value, locale) else value.toJson())
        }
        return out
    }

    private fun images(lit: Lit, locale: String): JsonNode =
        if (lit is Lit.Arr) Json.of(lit.items.map { renamed(it, IMAGE, locale) }) else lit.toJson()

    /** One member's wire value: `[]` and `false` for the defaults `lingara.manifest` fills, `null` for an absent optional one. */
    private fun member(from: String, v: Lit?, locale: String): JsonNode? = when {
        v != null -> present(from, v, locale)
        from == "context" || from == "scopes" -> Json.arr()
        from == "tutorNote" -> Json.of(false)
        else -> null
    }

    private fun present(from: String, v: Lit, locale: String): JsonNode = when (from) {
        "name", "description", "longDescription" -> localeMap(v, locale)
        "developer" -> renamed(v, DEVELOPER, locale)
        "images" -> images(v, locale)
        else -> v.toJson()
    }

    /** The wire manifest for the `manifest` object at [at]. */
    fun convert(members: List<Member>, at: Pos): JsonNode {
        members.firstOrNull { m -> KEYS.none { it.first == m.key } }?.let { throw Refusal.At("manifest_key", it.at) }
        if (REQUIRED.any { find(members, it) == null }) throw Refusal.At("manifest_key", at)
        val defaultLocale = find(members, "defaultLocale")
        val locale = (defaultLocale as? Lit.Str)?.s ?: "en"
        val out = Json.obj("manifest_version" to 1, "default_locale" to (defaultLocale?.toJson() ?: Json.of("en")))
        for ((from, to) in KEYS.drop(1)) {
            member(from, find(members, from), locale)?.let { out.set<JsonNode>(to, it) }
        }
        return out
    }
}
