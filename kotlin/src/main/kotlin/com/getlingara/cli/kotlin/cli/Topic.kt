// The flag groups every node or every dangerous command carries, and the
// [Topic] that groups commands (ADR 16.10.26af D2, D9).
package com.getlingara.cli.kotlin.cli

import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

/** `--json` and `--profile`, on every node; [Parse] reads the innermost that was given. */
class GlobalFlags : OptionGroup() {
    val json by option("--json", help = "Machine-readable output on stdout").flag()
    val profile by option("--profile", help = "The stored credentials to use", metavar = "name")

    companion object {
        val NAMES = setOf("--json", "--profile")
    }
}

/** The `confirm` group every dangerous command carries (16.10.26ad D5). Neither flag reads the environment or configuration. */
class ConfirmFlags : OptionGroup() {
    val yes by option("-y", "--yes", help = "Answer the y/n question; needs --confirm too").flag()
    val email by option("--confirm", help = "Your developer email, typed back; needs -y too", metavar = "email")
}

/** A group of commands; run bare, Clikt asks for help with `error = true`, which [Parse] makes `usage`. */
class Topic(name: String, summary: String, vararg children: Node) : Node(name, summary) {
    private val aliasMap = mutableMapOf<String, List<String>>()

    init {
        subcommands(*children)
    }

    /** Another spelling of a child topic: `client` for `oauth_client`. */
    fun alias(alias: String, child: String): Topic = apply { aliasMap[alias] = listOf(child) }

    override fun aliases(): Map<String, List<String>> = aliasMap

    override fun run() = Unit
}
