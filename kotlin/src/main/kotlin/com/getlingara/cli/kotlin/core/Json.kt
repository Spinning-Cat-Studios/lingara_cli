// JSON through Jackson's tree model, whose objects keep insertion order, so
// a body and a printed value carry their members in the order the server or
// the contract wrote them (the Rust kit's `preserve_order`). `path()` reads a
// missing member as a missing node, never an exception, so a handler reads a
// server value as leniently as the reference kits do.
package com.getlingara.cli.kotlin.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode

object Json {
    val mapper: ObjectMapper = ObjectMapper()
    private val nodes: JsonNodeFactory = JsonNodeFactory.instance

    fun obj(): ObjectNode = nodes.objectNode()

    fun arr(): ArrayNode = nodes.arrayNode()

    /** An object of `pairs`, each value through [of]. */
    fun obj(vararg pairs: Pair<String, Any?>): ObjectNode = obj().also { o -> pairs.forEach { (k, v) -> o.set<JsonNode>(k, of(v)) } }

    /** A Kotlin value as a node: strings, numbers, booleans, lists, nodes and null. */
    fun of(value: Any?): JsonNode = when (value) {
        null -> nodes.nullNode()
        is JsonNode -> value
        is String -> nodes.textNode(value)
        is Boolean -> nodes.booleanNode(value)
        is Int -> nodes.numberNode(value)
        is Long -> nodes.numberNode(value)
        is List<*> -> arr().also { a -> value.forEach { a.add(of(it)) } }
        else -> nodes.textNode(value.toString())
    }

    /** `text` as JSON; `null` when it is not. */
    fun parse(text: String): JsonNode? = runCatching { mapper.readTree(text) }.getOrNull()?.takeUnless { it.isMissingNode }

    fun parse(bytes: ByteArray): JsonNode? = parse(String(bytes, Charsets.UTF_8))

    /** Compact JSON, one line, as `serde_json::to_string` writes it. */
    fun write(node: JsonNode): String = mapper.writeValueAsString(node)
}

/** The member's text, or `""`. */
fun JsonNode.str(key: String): String = path(key).textValue() ?: ""

/** The member's text, or `null` when it is absent or not text. */
fun JsonNode.strOrNull(key: String): String? = path(key).textValue()

/** An array of text as strings; anything else is empty. */
fun JsonNode.strings(): List<String> = if (isArray) mapNotNull { it.textValue() } else emptyList()

/** The member as an array's items, or none. */
fun JsonNode.items(key: String): List<JsonNode> = path(key).let { if (it.isArray) it.toList() else emptyList() }
