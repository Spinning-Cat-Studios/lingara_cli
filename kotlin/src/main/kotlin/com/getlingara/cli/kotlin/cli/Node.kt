// The Clikt tree's two shapes (ADR 16.10.26af D2, D9): a [Topic] groups
// commands, a [Leaf] runs one. Every node carries [GlobalFlags], because
// Clikt has no global options and the schema's `--json` and `--profile` are
// accepted after the command as well as before it, as clap's are. Each
// option is registered through the helpers below, which read the kit-local
// table for its conversion.
package com.getlingara.cli.kotlin.cli

import com.getlingara.cli.kotlin.app.Ctx
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.OptionWithValues
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice

abstract class Node(name: String, private val summary: String) : CoreCliktCommand(name) {
    val globals by GlobalFlags()

    override fun help(context: Context): String = summary
}

abstract class Leaf(name: String, summary: String) : Node(name, summary) {
    /** The command, once parsing chose it. */
    abstract fun execute(ctx: Ctx)

    /** The flags given, for the schema's conflicts; only a leaf that has conflicts says. */
    open fun given(): Set<String> = emptySet()

    /** `oauth_client delete`: the command's path under the root, by its canonical names. */
    val path: String
        get() = generateSequence(currentContext) { it.parent }.map { it.command.commandName }.toList().reversed().drop(1).joinToString(" ")

    override fun run() {
        val given = given()
        Params.conflicts[path]?.firstOrNull { (a, b) -> a in given && b in given }?.let { (a, b) ->
            throw UsageError("--$a cannot be used with --$b")
        }
        (currentContext.findRoot().command as Root).chosen = this
    }

    private fun typed(flag: String, help: String, metavar: String?): OptionWithValues<String?, String, String> {
        val raw = option("--$flag", help = help, metavar = metavar)
        return when (val type = Params.type(flag)) {
            is ParamType.Choice -> raw.choice(*type.values.toTypedArray())
            ParamType.IntType -> raw.convert { v -> v.also { if (it.toIntOrNull() !in 1..65535) fail("$it is not a port from 1 to 65535") } }
            else -> raw
        }
    }

    private fun once(flag: String) = check(!Params.repeatable(flag)) { "--$flag is repeatable in the kit-local table" }

    private fun repeated(flag: String) = check(Params.repeatable(flag)) { "--$flag is not repeatable in the kit-local table" }

    protected fun opt(flag: String, help: String, metavar: String? = null) = typed(flag, help, metavar).also { once(flag) }

    protected fun need(flag: String, help: String, metavar: String? = null) = opt(flag, help, metavar).required()

    protected fun many(flag: String, help: String, metavar: String? = null) = typed(flag, help, metavar).also { repeated(flag) }.multiple()

    protected fun needMany(flag: String, help: String, metavar: String? = null) = typed(flag, help, metavar).also { repeated(flag) }.multiple(required = true)

    protected fun switch(flag: String, help: String) = option("--$flag", help = help).flag()

    protected fun arg(name: String) = argument(name)

    protected fun optArg(name: String) = argument(name).optional()
}
