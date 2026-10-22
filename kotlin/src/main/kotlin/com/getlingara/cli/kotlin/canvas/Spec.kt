// A field node's op body: the web's `specFromFields` then `specBody`
// (`testRun.ts`, `plan.ts`), over the node's saved fields. A create's client
// is always `billing_mode: allowance`; no body carries `enabled`,
// `api_version` or `client_type`. The app manifest is `lingara.v1`'s
// `manifest()` wire form, with the defaults it fills.
package com.getlingara.cli.kotlin.canvas

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.getlingara.cli.kotlin.core.Json

/** Why a node's fields are not its kind's spec. */
class Invalid(why: String) : Exception(why)

object Spec {
    private fun text(v: JsonNode, what: String): String = v.textValue() ?: throw Invalid("$what must be a string")

    private fun texts(v: JsonNode, what: String): List<String> {
        if (!v.isArray) throw Invalid("$what must be an array of strings")
        return v.map { text(it, what) }
    }

    private fun optionalTexts(v: JsonNode, what: String): List<String>? = if (v.isNull || v.isMissingNode) null else texts(v, what)

    /** A plain string is `{locale: s}`; a map must hold strings only. */
    private fun localeText(v: JsonNode, locale: String, what: String): JsonNode = when {
        v.isTextual -> Json.obj(locale to v.textValue())
        v.isObject && v.all { it.isTextual } -> v
        else -> throw Invalid("$what must be a string or an object of strings")
    }

    /** `{name, scopes, redirect_uris?}` plus `billing_mode` on a create. */
    fun client(fields: JsonNode, create: Boolean): JsonNode {
        val body = Json.obj("name" to text(fields.path("name"), "name"), "scopes" to texts(fields.path("scopes"), "scopes"))
        optionalTexts(fields.path("redirectUris"), "redirectUris")?.let { body.set<JsonNode>("redirect_uris", Json.of(it)) }
        if (create) body.put("billing_mode", "allowance")
        return body
    }

    fun webhook(fields: JsonNode): JsonNode = Json.obj("url" to text(fields.path("url"), "url"), "event_types" to texts(fields.path("events"), "events"))

    fun app(fields: JsonNode): JsonNode = Json.obj("manifest" to manifest(fields.path("manifest")))

    /** `lingara.v1`'s `manifest()`, in the order it writes the members. */
    fun manifest(o: JsonNode): JsonNode {
        if (!o.isObject) throw Invalid("manifest must be an object")
        val locale = if (o.path("defaultLocale").let { it.isNull || it.isMissingNode }) "en" else text(o.path("defaultLocale"), "defaultLocale")
        val m = Json.obj("manifest_version" to 1, "default_locale" to locale)
        m.set<JsonNode>("name", localeText(o.path("name"), locale, "name"))
        m.set<JsonNode>("description", localeText(o.path("description"), locale, "description"))
        m.put("render_url", text(o.path("renderUrl"), "renderUrl"))
        m.set<JsonNode>("slots", Json.of(texts(o.path("slots"), "slots")))
        m.set<JsonNode>("context", Json.of(optionalTexts(o.path("context"), "context") ?: emptyList<String>()))
        m.set<JsonNode>("scopes", Json.of(optionalTexts(o.path("scopes"), "scopes") ?: emptyList<String>()))
        m.put("tutor_note", o.path("tutorNote").asBoolean(false) && o.path("tutorNote").isBoolean)
        listing(o, locale, m)
        return m
    }

    /** The listing's three members, each written only when its option is set. */
    private fun listing(o: JsonNode, locale: String, m: ObjectNode) {
        val long = o.path("longDescription")
        if (!long.isNull && !long.isMissingNode) m.set<JsonNode>("long_description", localeText(long, locale, "longDescription"))
        val dev = o.path("developer")
        if (dev.isObject) {
            val d = Json.obj("name" to text(dev.path("name"), "developer.name"))
            for ((from, to) in listOf("url" to "url", "supportUrl" to "support_url")) {
                if (dev.has(from)) d.put(to, text(dev.path(from), from))
            }
            m.set<JsonNode>("developer", d)
        }
        val images = o.path("images")
        if (images.isArray) {
            val wire = images.map { i ->
                Json.obj("sha256" to text(i.path("sha256"), "images[].sha256"), "role" to text(i.path("role"), "images[].role"), "alt" to localeText(i.path("alt"), locale, "images[].alt"))
            }
            m.set<JsonNode>("images", Json.of(wire))
        }
    }

    /** The name an app op registers under: the manifest's in its default locale. */
    fun appName(body: JsonNode, fallback: String): String {
        val manifest = body.path("manifest")
        val locale = manifest.path("default_locale").textValue() ?: "en"
        val names = manifest.path("name")
        return names.path(locale).textValue() ?: names.firstOrNull { it.isTextual }?.textValue() ?: fallback
    }
}
