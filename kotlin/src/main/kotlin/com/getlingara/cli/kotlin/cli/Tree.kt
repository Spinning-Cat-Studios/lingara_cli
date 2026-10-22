// `lingara __tree` (ADR 16.10.26ab D4, 16.10.26af D2): the registered tree,
// read back from Clikt's own model rather than written twice. From Clikt:
// commands, topic aliases, option and argument names, `required`, and a
// flag's `bool` (a Clikt flag takes no value). From the kit-local table
// (`Params`): every other `type`, an enum's `values`, and repeatability. The
// global flags are the root's; their copies on every node (`GlobalFlags`)
// are the same two flags, so no command lists them. A command is dangerous
// exactly when it carries `--confirm`, the schema's own rule (16.10.26ab D2.4).
package com.getlingara.cli.kotlin.cli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.getlingara.cli.kotlin.core.Json
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.output.HelpFormatter
import com.github.ajalt.clikt.parameters.arguments.Argument
import com.github.ajalt.clikt.parameters.options.Option

object Tree {
    fun of(root: Root): JsonNode {
        val out = level(root)
        out.put("name", root.commandName)
        out.set<JsonNode>("global_flags", Json.of(flags(root, keepGlobals = true)))
        return out
    }

    private fun children(cmd: CoreCliktCommand): List<Node> = cmd.registeredSubcommands().map { it as Node }.filter { !it.hiddenFromHelp }

    /** A topic's commands and sub-topics. */
    private fun level(cmd: CoreCliktCommand): ObjectNode {
        val (topics, commands) = children(cmd).partition { it is Topic }
        return Json.obj(
            "commands" to commands.map { command(it, cmd) },
            "topics" to topics.map { topic(it, cmd) },
        )
    }

    /** The names [parent] maps onto [child]: `client` for `oauth_client`. */
    private fun aliases(child: Node, parent: CoreCliktCommand): List<String> =
        parent.aliases().filterValues { it == listOf(child.commandName) }.keys.sorted()

    private fun topic(t: Node, parent: CoreCliktCommand): JsonNode =
        level(t).also { it.put("name", t.commandName); it.set<JsonNode>("aliases", Json.of(aliases(t, parent))) }

    private fun command(c: Node, parent: CoreCliktCommand): JsonNode = Json.obj(
        "name" to c.commandName,
        "aliases" to aliases(c, parent),
        "dangerous" to c.registeredOptions().any { "--confirm" in it.names },
        "args" to c.registeredArguments().map(::positional),
        "flags" to flags(c, keepGlobals = false),
    )

    /** The command's own flags; a fresh context to read each one's help from, as Clikt renders it. */
    private fun flags(c: Node, keepGlobals: Boolean): List<JsonNode> {
        val context = c.resetContext()
        return c.registeredOptions()
            // `--help` and the root's `--version` are Clikt's eager options: the framework's, never a schema flag.
            .filter { o -> !o.eager }
            .filter { o -> keepGlobals || o.names.none { it in GlobalFlags.NAMES } }
            .map { param(it, context) }
    }

    private fun param(o: Option, context: Context): JsonNode {
        val long = o.names.first { it.startsWith("--") }.removePrefix("--")
        val type = if (o.nvalues.last == 0) null else Params.type(long)
        val out = Json.obj(
            "name" to long,
            "type" to (type?.word ?: "bool"),
            "required" to (HelpFormatter.Tags.REQUIRED in o.parameterHelp(context)?.tags.orEmpty()),
            "repeatable" to Params.repeatable(long),
        )
        o.names.firstOrNull { it.length == 2 && !it.startsWith("--") }?.let { out.put("short", it.removePrefix("-")) }
        if (type is ParamType.Choice) out.set<JsonNode>("values", Json.of(type.values))
        return out
    }

    private fun positional(a: Argument): JsonNode =
        Json.obj("name" to a.name, "type" to "string", "required" to a.required, "repeatable" to (a.nvalues < 0))
}
