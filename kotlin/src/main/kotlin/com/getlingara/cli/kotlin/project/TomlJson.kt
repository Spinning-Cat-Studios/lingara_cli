// `platform.toml` parsed by tomlj (ADR 16.10.26af D2) into the JSON its
// schema describes, keys in **file order**: tomlj's tables do not keep
// insertion order, and the first secret, or the first stray key, is the
// first in the file in every kit (16.10.26ae D4). A TOML date or time becomes
// a value that is neither string nor number, so no rule mistakes it for one.
package com.getlingara.cli.kotlin.project

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.core.Fail
import com.getlingara.cli.kotlin.core.Json
import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable
import java.time.temporal.TemporalAccessor

object TomlJson {
    /** [text] as JSON; a syntax error is [refuse]'s, with tomlj's first message. */
    fun parse(text: String, refuse: (String) -> Fail): JsonNode {
        val result = Toml.parse(text)
        result.errors().firstOrNull()?.let { throw refuse(it.toString()) }
        return table(result)
    }

    private fun table(t: TomlTable): JsonNode {
        val out = Json.obj()
        val ordered = t.keySet().sortedWith(compareBy({ t.inputPositionOf(listOf(it))?.line() ?: 0 }, { t.inputPositionOf(listOf(it))?.column() ?: 0 }))
        ordered.forEach { key -> out.set<JsonNode>(key, value(t.get(listOf(key)))) }
        return out
    }

    private fun value(v: Any?): JsonNode = when (v) {
        is TomlTable -> table(v)
        is TomlArray -> Json.arr().also { a -> (0 until v.size()).forEach { a.add(value(v.get(it))) } }
        is Long -> Json.of(v)
        is Double -> Json.mapper.nodeFactory.numberNode(v)
        is TemporalAccessor -> Json.mapper.nodeFactory.pojoNode(v)
        else -> Json.of(v)
    }
}
