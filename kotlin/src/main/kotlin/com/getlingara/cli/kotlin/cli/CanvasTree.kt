// `lingara canvas …` (ADR 16.10.26ad D1, D7): content through
// `/v1/canvases`, lifecycle through `/platform/objects`.
package com.getlingara.cli.kotlin.cli

import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.canvas.Apply
import com.getlingara.cli.kotlin.canvas.ApplyArgs
import com.getlingara.cli.kotlin.commands.Canvas
import java.nio.file.Path

class CanvasCreate : Leaf("create", "Create a canvas, from a definition file or empty.") {
    private val name by opt("name", "The canvas's name")
    private val file by opt("file", "The definition; absent, an empty one", "definition.json")

    override fun execute(ctx: Ctx) = Canvas.create(ctx, name, file?.let(Path::of))
}

class CanvasList : Leaf("list", "List your canvases.") {
    override fun execute(ctx: Ctx) = Canvas.list(ctx)
}

class CanvasGet : Leaf("get", "Show one canvas.") {
    private val id by arg("id")

    override fun execute(ctx: Ctx) = ctx.out.value(Canvas.get(ctx, id))
}

class CanvasUpdate : Leaf("update", "Rename a canvas.") {
    private val id by arg("id")
    private val name by need("name", "The new name")

    override fun execute(ctx: Ctx) = Canvas.update(ctx, id, name)
}

class CanvasDraft : Leaf("draft", "Save a definition as the canvas's draft. Creates nothing else.") {
    private val id by arg("id")
    private val file by need("file", "The definition", "definition.json")

    override fun execute(ctx: Ctx) = Canvas.draft(ctx, id, Path.of(file))
}

class CanvasApply : Leaf("apply", "Create the canvas's objects by running this CLI's own commands, then activate it.") {
    private val id by arg("id")
    private val dryRun by switch("dry-run", "Print what each object would do, and write nothing")
    private val printCommands by switch("print-commands", "Print the commands apply would run, and write nothing")

    override fun given() = setOfNotNull("dry-run".takeIf { dryRun }, "print-commands".takeIf { printCommands })

    override fun execute(ctx: Ctx) = Apply.run(ctx, ApplyArgs(id, dryRun, printCommands))
}

object CanvasTree {
    fun build(): Topic = Topic(
        "canvas",
        "Canvases: builder flows that create their objects only when applied.",
        CanvasCreate(), CanvasList(), CanvasGet(), CanvasUpdate(), CanvasDraft(), CanvasApply(),
        DeleteLeaf("Delete a canvas, or one version. The objects it made stay.", Canvas::delete),
    )
}
