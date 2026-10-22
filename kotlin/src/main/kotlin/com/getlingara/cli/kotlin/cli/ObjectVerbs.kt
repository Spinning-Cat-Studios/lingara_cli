// The verbs every object kind shares (ADR 16.10.26ad D1): `list`, `get <id>`
// and `delete <id> [--version] -y --confirm`, each one class over a [Kind].
// A command is dangerous exactly when it carries [ConfirmFlags], the
// schema's own rule (16.10.26ab D2.4), which `__tree` reads back.
package com.getlingara.cli.kotlin.cli

import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.commands.Columns
import com.getlingara.cli.kotlin.commands.ConfirmArgs
import com.getlingara.cli.kotlin.commands.Consequence
import com.getlingara.cli.kotlin.commands.DeleteArgs
import com.getlingara.cli.kotlin.commands.Deletes
import com.getlingara.cli.kotlin.commands.Kind
import com.getlingara.cli.kotlin.commands.Objects
import com.github.ajalt.clikt.parameters.groups.provideDelegate

class ListLeaf(private val kind: Kind, private val columns: Columns, summary: String) : Leaf("list", summary) {
    override fun execute(ctx: Ctx) = Objects.printList(ctx, kind, columns)
}

class GetLeaf(private val kind: Kind, summary: String) : Leaf("get", summary) {
    private val id by arg("id")

    override fun execute(ctx: Ctx) = Objects.printGet(ctx, kind, id)
}

/** A leaf that carries the confirm group: a dangerous command. */
abstract class Dangerous(name: String, summary: String) : Leaf(name, summary) {
    private val confirmFlags by ConfirmFlags()

    val confirm: ConfirmArgs get() = ConfirmArgs(confirmFlags.yes, confirmFlags.email)
}

/** Every object `delete`: the id, an optional version, the ritual; [run] is the kind's delete. */
open class DeleteLeaf(
    summary: String,
    private val run: (Ctx, DeleteArgs) -> Unit,
) : Dangerous("delete", summary) {
    private val id by arg("id")
    private val version by opt("version", "Delete only this version", "semver")

    constructor(kind: Kind, summary: String, consequence: Consequence) : this(summary, { ctx, args -> Deletes.deleteObject(ctx, kind, args, consequence) })

    override fun execute(ctx: Ctx) = run(ctx, DeleteArgs(id, version, confirm))
}
