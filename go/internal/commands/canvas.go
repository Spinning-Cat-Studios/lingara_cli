package commands

import (
	"github.com/spf13/cobra"

	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/canvasapply"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/objects"
)

// lingara canvas … (16.10.26ad D1, D7): content through /v1/canvases,
// lifecycle through /platform/objects, apply through package canvasapply.

func (b *builder) canvas(root *cobra.Command) {
	g := b.group(root, "canvas", "Canvases: builder flows that create their objects only when applied.")
	b.leaf(g, "create", "Create a canvas, from a definition file or empty.", func(c *app.Ctx, in input) *core.Fail {
		return objects.CanvasCreate(c, in.opt("name"), in.str("file"))
	}).str("name", "The canvas's name").str("file", "The definition; absent, an empty one")
	b.leaf(g, "list", "List your canvases.", func(c *app.Ctx, _ input) *core.Fail { return objects.CanvasList(c) })
	b.leaf(g, "get", "Show one canvas.", func(c *app.Ctx, in input) *core.Fail { return objects.CanvasPrint(c, in.arg(0)) })
	b.leaf(g, "update", "Rename a canvas.", func(c *app.Ctx, in input) *core.Fail {
		return objects.CanvasRename(c, in.arg(0), in.str("name"))
	}).str("name", "The new name").need("name")
	b.leaf(g, "draft", "Save a definition as the canvas's draft. Creates nothing else.", func(c *app.Ctx, in input) *core.Fail {
		return objects.CanvasDraft(c, in.arg(0), in.str("file"))
	}).str("file", "The definition").need("file")
	b.leaf(g, "apply", "Create the canvas's objects by running this CLI's own commands, then activate it.",
		func(c *app.Ctx, in input) *core.Fail {
			return canvasapply.Run(c, in.arg(0), canvasapply.Options{DryRun: in.flag("dry-run"), PrintCommands: in.flag("print-commands")})
		}).
		boolean("dry-run", "Print what each object would do, and write nothing").
		boolean("print-commands", "Print the commands apply would run, and write nothing").
		conflicts([2]string{"dry-run", "print-commands"})
	b.deleteLeaf(g, "Delete a canvas, or one version. The objects it made stay.", func(c *app.Ctx, in input) *core.Fail {
		return objects.CanvasDelete(c, deleteArgs(in))
	})
}
