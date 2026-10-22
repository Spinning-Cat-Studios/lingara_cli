package commands

import (
	"github.com/spf13/cobra"

	"getlingara.com/cli/internal/app"
	"getlingara.com/cli/internal/apps"
	"getlingara.com/cli/internal/core"
	"getlingara.com/cli/internal/objects"
)

// lingara apps … (16.10.26ad D1; 16.10.26ae D1, D6, D7). create and update
// each carry both forms; their one dispatch point is package apps'.

const configUsage = "The platform.toml to read; default the nearest at or above this directory"

func (b *builder) apps(root *cobra.Command) {
	g := b.group(root, "apps", "Apps that render cards inside Lingara.")
	b.appsCreate(g)
	b.leaf(g, "list", "List your apps, or the apps platform.toml pins.", func(c *app.Ctx, in input) *core.Fail {
		if in.flag("pinned") {
			return apps.ListPinned(c, in.str("config"))
		}
		return objects.PrintList(c, objects.App, apps.Columns)
	}).boolean("pinned", "List platform.toml's rows instead, reading nothing from the platform").str("config", configUsage)
	b.leaf(g, "get", "Show one app.", func(c *app.Ctx, in input) *core.Fail { return objects.PrintGet(c, objects.App, in.arg(0)) })
	b.leaf(g, "update", "Without --manifest, push the project's manifest to its dev version (--version cuts a draft); "+
		"with an id and --manifest, make a new version from a file.", func(c *app.Ctx, in input) *core.Fail {
		return apps.Update(c, apps.UpdateArgs{ID: in.arg(0), Manifest: in.str("manifest"), Version: in.opt("version"), Config: in.str("config")})
	}).str("manifest", "The app's manifest file: makes a new version").
		str("version", "Cut a draft version from the project's manifest").str("config", configUsage).
		conflicts([2]string{"manifest", "version"})
	b.appsDev(g)
	b.leaf(g, "status", "Show where the app's dev version renders from: live, parked, or the serving version.",
		func(c *app.Ctx, in input) *core.Fail { return apps.Status(c, in.arg(0), in.str("config")) }).str("config", configUsage)
	b.leaf(g, "pin", "Add an app to platform.toml (and its example), by id or by name.", func(c *app.Ctx, in input) *core.Fail {
		return apps.Pin(c, in.arg(0), in.str("path"), in.str("config"))
	}).str("path", "The project directory; needed for a name").str("config", configUsage)
	b.leaf(g, "unpin", "Remove an app's row from platform.toml; the example changes by review.", func(c *app.Ctx, in input) *core.Fail {
		return apps.Unpin(c, in.arg(0), in.str("config"))
	}).str("config", configUsage)
	b.deleteLeaf(g, "Delete an app, or one of its versions.", func(c *app.Ctx, in input) *core.Fail {
		return objects.DeleteObject(c, objects.App, deleteArgs(in), objects.ParentConsequence("consequence_app"))
	})
	b.leaf(g, "submit", "Submit a version of an app for review, to make it public.", func(c *app.Ctx, in input) *core.Fail {
		return apps.Submit(c, in.arg(0), in.str("version"))
	}).str("version", "The version to submit").need("version")
	v := b.group(g, "versions", "An app's versions.")
	b.leaf(v, "list", "List an app's versions.", func(c *app.Ctx, in input) *core.Fail { return apps.ListVersions(c, in.arg(0)) })
	b.leaf(v, "publish", "Publish an approved version, or a private app's draft.", func(c *app.Ctx, in input) *core.Fail {
		return apps.VersionOp(c, "publish", in.arg(0), in.arg(1))
	})
	b.leaf(v, "withdraw", "Withdraw a version.", func(c *app.Ctx, in input) *core.Fail {
		return apps.VersionOp(c, "withdraw", in.arg(0), in.arg(1))
	})
}

func (b *builder) appsCreate(g *cobra.Command) {
	b.leaf(g, "create", "Scaffold an app project and register it; or, with --client and --manifest, register an app from a manifest file.",
		func(c *app.Ctx, in input) *core.Fail {
			return apps.CreateEither(c, apps.CreateArgs{
				Name: in.arg(0), Client: in.str("client"), Manifest: in.str("manifest"), Lang: in.str("lang"),
				Bare: in.flag("bare"), Dir: in.str("dir"), NoRegister: in.flag("no-register"), Description: in.opt("description"),
				Port: in.port("port"), Slots: in.list("slot"), Context: in.list("context"), Config: in.str("config"),
			})
		}).
		str("client", "The OAuth client's id (with --manifest)").str("manifest", "The app's manifest file (with --client)").
		str("lang", "The app's language").
		boolean("bare", "The manifest as code in the app's language, and a handler on the kit's primitives").
		str("dir", "Where to write the project; default ./<name>").
		boolean("no-register", "Scaffold only; register later with apps update").
		str("description", "One sentence about the app").port("port", "The port the app listens on; default 8787").
		strs("slot", "Where the app's card may appear; default home.side").
		strs("context", "A context slice the app receives").str("config", configUsage).
		conflicts([2]string{"manifest", "lang"}, [2]string{"manifest", "bare"}, [2]string{"manifest", "dir"},
			[2]string{"manifest", "no-register"}, [2]string{"manifest", "description"}, [2]string{"manifest", "port"},
			[2]string{"manifest", "slot"}, [2]string{"manifest", "context"},
			[2]string{"client", "lang"}, [2]string{"client", "bare"}, [2]string{"client", "dir"})
}

func (b *builder) appsDev(g *cobra.Command) {
	b.leaf(g, "dev", "Run the app behind a tunnel and point your private dev version at it, until Ctrl+C.",
		func(c *app.Ctx, in input) *core.Fail {
			return apps.Dev(c, apps.DevArgs{
				Name: in.arg(0), Port: in.port("port"), Tunnel: in.str("tunnel"), RenderURL: in.opt("render-url"),
				TakeOver: in.flag("take-over"), WriteEnv: in.str("write-env"), ShowEnv: in.flag("show-env"),
				Reset: in.flag("reset"), Config: in.str("config"),
			})
		}).
		port("port", "The port the app listens on; default the row's").
		str("tunnel", "The tunnel to open; none needs --render-url").
		str("render-url", "With --tunnel none, the URL the dev version renders from").
		boolean("take-over", "End another terminal's session on this app first").
		str("write-env", "Also write the session's variables to this gitignored file, mode 0600").
		boolean("show-env", "List the names of the variables the app receives").
		boolean("reset", "Return your install to the serving version, and do nothing else").
		str("config", configUsage).
		conflicts([2]string{"reset", "port"}, [2]string{"reset", "tunnel"}, [2]string{"reset", "render-url"},
			[2]string{"reset", "take-over"}, [2]string{"reset", "write-env"}, [2]string{"reset", "show-env"})
}
