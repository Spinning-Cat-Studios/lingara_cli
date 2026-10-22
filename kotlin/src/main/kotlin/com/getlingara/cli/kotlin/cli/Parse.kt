// argv through Clikt, and Clikt's answers bent to the contract (ADR
// 16.10.26af D9). `--help` and `--version` print to stdout and exit 0;
// anything else Clikt refuses is `usage` (2), never Clikt's own exit 1: a
// [UsageError], and a bare group's `PrintHelpMessage(error = true)`. Nothing
// here reaches a server.
package com.getlingara.cli.kotlin.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.parse
import com.getlingara.cli.kotlin.core.Fail

sealed interface Parsed {
    /** Help or the version: printed on stdout, exit 0. */
    class Printed(val text: String) : Parsed

    /** A leaf to run, with the global flags the innermost node was given. */
    class Chosen(val leaf: Leaf, val json: Boolean, val profile: String?) : Parsed
}

object Parse {
    fun parse(argv: List<String>): Parsed {
        val root = Root.build()
        try {
            root.parse(argv)
        } catch (e: PrintHelpMessage) {
            val help = root.getFormattedHelp(e).orEmpty()
            if (!e.error) return Parsed.Printed(help.trimEnd() + "\n")
            val path = e.context?.commandNameWithParents()?.joinToString(" ") ?: "lingara"
            throw usage("$path needs a command", help)
        } catch (e: PrintMessage) {
            return Parsed.Printed(e.message.orEmpty() + "\n")
        } catch (e: CliktError) {
            val shown = root.getFormattedHelp(e).orEmpty()
            val first = shown.lines().firstOrNull { it.startsWith("Error: ") }?.removePrefix("Error: ") ?: e.message ?: "usage"
            throw usage(first, shown.lines().filterNot { it.startsWith("Error: ") }.joinToString("\n"))
        }
        val leaf = root.chosen ?: throw Fail.usage("no command")
        return chosen(leaf)
    }

    /** `usage`, with the help Clikt rendered as the hint lines a human reads under it. */
    private fun usage(first: String, help: String): Fail =
        Fail.usage(first).also { fail -> help.lines().filter { it.isNotBlank() }.forEach { fail.hint(it) } }

    /** The innermost `--json` / `--profile` given: a leaf's own, else its topics', else the root's. */
    private fun chosen(leaf: Leaf): Parsed.Chosen {
        val chain = generateSequence(leaf.currentContext) { it.parent }.map { it.command as Node }.toList()
        val json = chain.any { it.globals.json }
        val profile = chain.firstNotNullOfOrNull { it.globals.profile }
        return Parsed.Chosen(leaf, json, profile)
    }
}
