// A canvas definition (Backend 2.10.26e §2), as far as apply reads it: nodes
// with their kind, fields and whether a script runs them, the edges, and the
// order the web's `flowDefinition.ts` walks them in.
package com.getlingara.cli.kotlin.canvas

import com.fasterxml.jackson.databind.JsonNode
import com.getlingara.cli.kotlin.core.items
import com.getlingara.cli.kotlin.core.str

/** One node; [custom] marks a script node, which runs only in the browser's sandbox (2.10.26b D5). */
data class Node(val id: String, val kind: String, val fields: JsonNode, val custom: Boolean)

data class Definition(val nodes: List<Node>, val edges: List<Pair<String, String>>) {
    /** Kahn's algorithm, ties broken by node order, as `topoOrder`; `null` on a cycle. */
    fun topoOrder(): List<Node>? {
        val indegree = nodes.map { n -> edges.count { it.second == n.id } }.toMutableList()
        val done = BooleanArray(nodes.size)
        val order = mutableListOf<Node>()
        while (order.size < nodes.size) {
            val next = nodes.indices.firstOrNull { !done[it] && indegree[it] == 0 } ?: return null
            done[next] = true
            order.add(nodes[next])
            for ((from, to) in edges) {
                val j = nodes.indexOfFirst { it.id == to }
                if (from == nodes[next].id && j >= 0) indegree[j] = maxOf(0, indegree[j] - 1)
            }
        }
        return order
    }

    /** A dependent node's client: its one direct upstream `client` node, or `null` for zero or two (`clientHandleOf`). */
    fun clientOf(handle: String): String? {
        val parents = nodes.filter { n -> n.kind == "client" && edges.any { it.first == n.id && it.second == handle } }
        return parents.singleOrNull()?.id
    }

    companion object {
        fun read(value: JsonNode): Definition = Definition(
            nodes = value.items("nodes").map { n ->
                Node(n.str("id"), n.str("kind"), n.path("fields"), n.path("custom").asBoolean(false) || n.path("script").isTextual)
            },
            edges = value.items("edges").map { it.str("from") to it.str("to") },
        )
    }
}
