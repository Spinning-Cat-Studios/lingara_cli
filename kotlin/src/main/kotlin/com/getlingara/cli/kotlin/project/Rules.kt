// `spec/platform-toml.schema.json`'s rules over the parsed file. They are
// written out first, in the reference kits' order and words, so a file one
// kit refuses every kit refuses with the same reason; then the schema itself
// judges what is left through networknt's validator (ADR 16.10.26af D2), so
// the committed schema, not only this file's reading of it, is the authority.
package com.getlingara.cli.kotlin.project

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.core.Assets
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion

object Rules {
    private val rootKeys = listOf("schema", "platform", "apps")
    private val platformKeys = listOf("api", "profile")
    private val rowKeys = listOf("name", "id", "path", "port", "start", "render_path", "manifest", "manifest_command", "credentials", "env_file", "env")
    private val credentials = listOf("app_secret", "client_credentials")

    /** A valid app name: a lowercase letter, then up to 39 lowercase letters, digits or hyphens. */
    fun appNameOk(name: String): Boolean =
        name.length in 1..40 && name[0] in 'a'..'z' && name.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }

    /** The first rule [value] breaks, as the reason `platform_toml_schema` prints; `null` when it keeps them all. */
    fun check(value: JsonNode): String? = handWritten(value) ?: bySchema(value)

    private fun handWritten(root: JsonNode): String? {
        val schema = root.get("schema")
        return when {
            schema == null -> "schema = 1 is missing"
            !(schema.isIntegralNumber && schema.asLong() == 1L) -> "schema is $schema; this lingara reads 1"
            else -> closed(root, rootKeys, "") ?: root.get("platform")?.let(::checkPlatform) ?: checkApps(root.get("apps"))
        }
    }

    private fun checkApps(apps: JsonNode?): String? = when {
        apps == null -> null
        !apps.isArray -> "apps must be [[apps]] rows"
        else -> apps.withIndex().firstNotNullOfOrNull { (i, r) -> checkRow(r, "apps[$i].") }
    }

    private fun closed(table: JsonNode, allowed: List<String>, at: String): String? =
        table.fieldNames().asSequence().firstOrNull { it !in allowed }?.let { "$at$it is not a key this file takes" }

    private fun checkPlatform(value: JsonNode): String? {
        if (!value.isObject) return "[platform] must be a table"
        closed(value, platformKeys, "platform.")?.let { return it }
        val api = value.get("api")
        if (api != null && !(api.isTextual && (api.textValue().startsWith("https://") || api.textValue().startsWith("http://")))) {
            return "platform.api must be an http(s) URL"
        }
        return nonempty(value, "profile", "platform.")
    }

    private fun nonempty(table: JsonNode, key: String, at: String): String? {
        val v = table.get(key) ?: return null
        return if (v.isTextual && v.textValue().isNotEmpty()) null else "$at$key must be a non-empty string"
    }

    private fun checkRow(row: JsonNode, at: String): String? {
        if (!row.isObject) return "$at must be a table"
        closed(row, rowKeys, at)?.let { return it }
        if (!(row.path("name").isTextual && appNameOk(row.path("name").textValue()))) {
            return "${at}name must be a lowercase letter, then up to 39 lowercase letters, digits or hyphens"
        }
        if (!row.has("path")) return "${at}path is missing"
        listOf("id", "path", "start", "manifest", "manifest_command", "env_file").firstNotNullOfOrNull { nonempty(row, it, at) }?.let { return it }
        return checkValues(row, at) ?: checkCredentials(row, at) ?: checkEnv(row, at)
    }

    private fun checkValues(row: JsonNode, at: String): String? {
        val port = row.get("port")
        val renderPath = row.get("render_path")
        return when {
            port != null && !(port.isIntegralNumber && port.asLong() in 1..65535) -> "${at}port must be a whole number from 1 to 65535"
            renderPath != null && !(renderPath.isTextual && renderPath.textValue().startsWith("/")) -> "${at}render_path must start with /"
            row.has("manifest") && row.has("manifest_command") -> "${at}manifest and manifest_command are one or the other"
            else -> null
        }
    }

    private fun checkCredentials(row: JsonNode, at: String): String? {
        val value = row.get("credentials") ?: return null
        if (!value.isArray) return "${at}credentials must be a list"
        val items = value.map { it.textValue() }
        val ok = items.all { it in credentials } && items.toSet().size == items.size
        return if (ok) null else "${at}credentials takes app_secret and client_credentials, each once"
    }

    private fun checkEnv(row: JsonNode, at: String): String? {
        val value = row.get("env") ?: return null
        if (!value.isObject) return "${at}env must be a table"
        return value.properties().firstOrNull { !it.value.isTextual }?.let { "${at}env.${it.key} must be a string" }
    }

    private val schema by lazy {
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(Assets.text("platform-toml.schema.json"))
    }

    /** The committed schema's verdict, its first message as the reason. */
    private fun bySchema(value: JsonNode): String? = schema.validate(value).firstOrNull()?.message
}
