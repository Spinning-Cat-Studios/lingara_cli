// Contract B1's human table, rendered here rather than by a table library,
// because those draw borders and wrap cells, which B1 forbids (16.10.26ad D6).
package com.getlingara.cli.kotlin.output

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.core.Json

object Table {
    /** An empty value is `-`, so no cell is blank and the split stays exact. */
    private fun shown(cell: String): String = cell.ifEmpty { "-" }

    /** Width in characters, as the eye counts them: code points, not UTF-16 units. */
    private fun width(text: String): Int = text.codePointCount(0, text.length)

    /**
     * A header row and the rows under it: left-aligned, padded to the widest
     * cell, two spaces apart, no trailing whitespace, never truncated.
     */
    fun render(columns: List<String>, rows: List<List<String>>): String {
        val all = listOf(columns) + rows
        val widths = columns.indices.map { i -> all.maxOf { r -> width(shown(r.getOrElse(i) { "" })) } }
        return all.joinToString("") { row ->
            val cells = columns.indices.map { i ->
                val text = shown(row.getOrElse(i) { "" })
                text + " ".repeat(widths[i] - width(text))
            }
            cells.joinToString("  ").trimEnd() + "\n"
        }
    }

    /**
     * One JSON value as a cell: a string as itself, a list of scalars joined
     * with commas, `null` and an empty list as `-`, anything else compact JSON.
     */
    fun cell(value: JsonNode): String = when {
        value.isNull || value.isMissingNode -> "-"
        value.isTextual -> value.textValue()
        value.isArray && value.isEmpty -> "-"
        value.isArray && value.all { !it.isContainerNode } -> value.joinToString(",") { cell(it) }
        else -> Json.write(value)
    }

    /** A `get`: B1's two-column `FIELD  VALUE` form, one row per member. */
    fun fields(value: JsonNode): List<List<String>> {
        if (!value.isObject) return listOf(listOf("value", cell(value)))
        return value.properties().map { (k, v) -> listOf(k, cell(v)) }
    }
}
