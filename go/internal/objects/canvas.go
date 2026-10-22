package objects

import (
	"os"

	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/assets"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/httpx"
	"getlingara.com/cli/internal/jsonv"
)

// lingara canvas … (16.10.26ad D1, D2, D7). Content goes through
// /v1/canvases; lifecycle (delete with the typed email) through
// /platform/objects, after canvas.lookup turns the builder's id into the
// platform object's. apply is package canvasapply's.

// Canvas is the canvas kind.
var Canvas = Kind{"canvas", "canvas", "canvas"}

// CanvasColumns is canvas list's.
var CanvasColumns = Columns("ID", "id", "NAME", "name", "DRAFT", "has_draft", "ACTIVE", "active_revision", "UPDATED", "updated_at")

// BuilderHint is for a /v1/canvases 404 while the builder flag is off
// (16.10.26x D3): where no id could be wrong, say what it probably means.
func BuilderHint(fail *core.Fail) *core.Fail {
	if fail != nil && fail.Exit == core.ExitNotFound {
		return fail.Hint("the canvas builder may not be enabled for this account")
	}
	return fail
}

func printed(c *app.Ctx, v any, fail *core.Fail) *core.Fail {
	if fail != nil {
		return fail
	}
	c.Out.Value(v)
	return nil
}

// CanvasGet is the /v1 read.
func CanvasGet(c *app.Ctx, id string) (any, *core.Fail) {
	return c.Call(httpx.Request{Route: httpx.Fixed("canvas.get"), Vars: idVar(id)})
}

// CanvasPrint prints one canvas.
func CanvasPrint(c *app.Ctx, id string) *core.Fail {
	v, fail := CanvasGet(c, id)
	return printed(c, v, fail)
}

// Definition reads a definition file.
func Definition(path string) (any, *core.Fail) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, core.Usage(path + ": " + err.Error())
	}
	v, err := jsonv.Parse(data)
	if err != nil {
		return nil, core.Usage(path + " is not JSON: " + err.Error())
	}
	return v, nil
}

// CanvasCreate: createCanvas answers the canvas itself, so there is no
// final read.
func CanvasCreate(c *app.Ctx, name *string, file string) *core.Fail {
	var def any = jsonv.Obj("schema", 2, "nodes", []any{}, "edges", []any{})
	if file != "" {
		var fail *core.Fail
		if def, fail = Definition(file); fail != nil {
			return fail
		}
	}
	body := jsonv.Obj("definition", def)
	if name != nil {
		body.Set("name", *name)
	}
	v, fail := c.Call(httpx.Request{Route: httpx.Fixed("canvas.create"), JSON: body})
	return BuilderHint(printed(c, v, fail))
}

// CanvasList is every page of the canvases.
func CanvasList(c *app.Ctx) *core.Fail {
	items, fail := Pages(c, httpx.Fixed("canvas.list"), nil, nil)
	if fail != nil {
		return BuilderHint(fail)
	}
	c.Out.List(items, CanvasColumns)
	return nil
}

// CanvasRename renames a canvas.
func CanvasRename(c *app.Ctx, id, name string) *core.Fail {
	v, fail := c.Call(httpx.Request{Route: httpx.Fixed("canvas.rename"), Vars: idVar(id), JSON: jsonv.Obj("name", name)})
	return printed(c, v, fail)
}

// CanvasDraft saves the definition at the draft's draft_version (0: a new
// draft). It creates nothing else: that is apply's alone (B7).
func CanvasDraft(c *app.Ctx, id, file string) *core.Fail {
	def, fail := Definition(file)
	if fail != nil {
		return fail
	}
	canvas, fail := CanvasGet(c, id)
	if fail != nil {
		return fail
	}
	draftVersion, ok := jsonv.Int(jsonv.Get(canvas, "draft", "draft_version"))
	if !ok {
		draftVersion = 0
	}
	body := jsonv.Obj("definition", def, "draft_version", draftVersion)
	v, fail := c.Call(httpx.Request{Route: httpx.Fixed("canvas.put_draft"), Vars: idVar(id), JSON: body})
	return printed(c, v, fail)
}

// lookup is the canvas object behind a /v1/canvases id (16.10.26v §8).
func lookup(c *app.Ctx, canvasID string) (string, *core.Fail) {
	query := []httpx.Pair{{"kind", "canvas"}, {"runtime_id", canvasID}}
	page, fail := c.Call(httpx.Request{Route: httpx.Fixed("canvas.lookup"), Query: query})
	if fail != nil {
		return "", fail
	}
	items := jsonv.Arr(jsonv.Get(page, "items"))
	if len(items) > 0 {
		if id, ok := jsonv.Str(jsonv.Get(items[0], "id")); ok {
			return id, nil
		}
	}
	return "", core.NewFail(core.ExitNotFound, "object_not_found", "no canvas "+canvasID)
}

// CanvasDelete deletes a canvas (or one version) through its platform
// object.
func CanvasDelete(c *app.Ctx, args DeleteArgs) *core.Fail {
	found := ""
	email, fail := core.ConfirmDestructive(c, args.Confirm, func() (core.Prompt, *core.Fail) {
		platformID, fail := lookup(c, args.ID)
		if fail != nil {
			return core.Prompt{}, fail
		}
		route := httpx.ObjectNamed("canvas.object_get", "get")
		object, fail := c.Call(httpx.Request{Route: route, Vars: idVar(platformID)})
		if fail != nil {
			return core.Prompt{}, fail
		}
		found = platformID
		return NewPrompt(Canvas, object, args.ID, args.Version, func(any) string { return assets.Message("consequence_canvas") }), nil
	})
	if fail != nil {
		return fail
	}
	if found == "" {
		if found, fail = lookup(c, args.ID); fail != nil {
			return fail
		}
	}
	if _, fail := Delete(c, Canvas, found, args.Version, email); fail != nil {
		return fail
	}
	c.Out.Nothing()
	return nil
}
