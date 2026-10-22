// `lingara apps …` (ADR 16.10.26ad D1; 16.10.26ae D1, D6, D7). `create` and
// `update` each carry both forms, the registration one (`--manifest`) and
// the project one; their one dispatch point is `apps.Apps`.
package com.getlingara.cli.kotlin.cli

import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.apps.Apps
import com.getlingara.cli.kotlin.apps.CreateArgs
import com.getlingara.cli.kotlin.apps.Dev
import com.getlingara.cli.kotlin.apps.DevArgs
import com.getlingara.cli.kotlin.apps.Local
import com.getlingara.cli.kotlin.apps.UpdateArgs
import com.getlingara.cli.kotlin.commands.Deletes
import com.getlingara.cli.kotlin.commands.Kind
import com.getlingara.cli.kotlin.commands.Objects
import com.getlingara.cli.kotlin.http.Op
import java.nio.file.Path

private const val CONFIG_HELP = "The platform.toml to read; default the nearest at or above this directory"

class AppsCreate : Leaf(
    "create",
    "Scaffold an app project and register it; or, with --client and --manifest, register an app from a manifest file.",
) {
    private val name by arg("name")
    private val client by opt("client", "The OAuth client's id (with --manifest)", "id")
    private val manifest by opt("manifest", "The app's manifest file (with --client)", "path")
    private val lang by opt("lang", "The app's language", "app-kit-lang")
    private val bare by switch("bare", "The manifest as code in the app's language, and a handler on the kit's primitives")
    private val dir by opt("dir", "Where to write the project; default ./<name>", "path")
    private val noRegister by switch("no-register", "Scaffold only; register later with apps update")
    private val description by opt("description", "One sentence about the app", "text")
    private val port by opt("port", "The port the app listens on; default 8787", "n")
    private val slot by many("slot", "Where the app's card may appear; default home.side", "slot")
    private val context by many("context", "A context slice the app receives", "slice")
    private val config by opt("config", CONFIG_HELP, "path")

    override fun given() = setOfNotNull(
        "client".takeIf { client != null }, "manifest".takeIf { manifest != null }, "lang".takeIf { lang != null },
        "bare".takeIf { bare }, "dir".takeIf { dir != null }, "no-register".takeIf { noRegister },
        "description".takeIf { description != null }, "port".takeIf { port != null },
        "slot".takeIf { slot.isNotEmpty() }, "context".takeIf { context.isNotEmpty() },
    )

    override fun execute(ctx: Ctx) = Apps.createEither(
        ctx,
        CreateArgs(name, client, manifest?.let(Path::of), lang, bare, dir?.let(Path::of), noRegister, description, port?.toInt(), slot, context, config),
    )
}

class AppsList : Leaf("list", "List your apps, or the apps platform.toml pins.") {
    private val pinned by switch("pinned", "List platform.toml's rows instead, reading nothing from the platform")
    private val config by opt("config", CONFIG_HELP, "path")

    override fun execute(ctx: Ctx) = if (pinned) Local.listPinned(ctx, config) else Objects.printList(ctx, Kind.APP, Apps.COLUMNS)
}

class AppsUpdate : Leaf(
    "update",
    "Without --manifest, push the project's manifest to its dev version (--version cuts a draft); with an id and --manifest, make a new version from a file.",
) {
    private val id by optArg("id")
    private val manifest by opt("manifest", "The app's manifest file: makes a new version", "path")
    private val version by opt("version", "Cut a draft version from the project's manifest", "semver")
    private val config by opt("config", CONFIG_HELP, "path")

    override fun given() = setOfNotNull("manifest".takeIf { manifest != null }, "version".takeIf { version != null })

    override fun execute(ctx: Ctx) = Apps.update(ctx, UpdateArgs(id, manifest?.let(Path::of), version, config))
}

class AppsDev : Leaf("dev", "Run the app behind a tunnel and point your private dev version at it, until Ctrl+C.") {
    private val name by optArg("name")
    private val port by opt("port", "The port the app listens on; default the row's", "n")
    private val tunnel by opt("tunnel", "The tunnel to open; none needs --render-url")
    private val renderUrl by opt("render-url", "With --tunnel none, the URL the dev version renders from", "https-url")
    private val takeOver by switch("take-over", "End another terminal's session on this app first")
    private val writeEnv by opt("write-env", "Also write the session's variables to this gitignored file, mode 0600", "file")
    private val showEnv by switch("show-env", "List the names of the variables the app receives")
    private val reset by switch("reset", "Return your install to the serving version, and do nothing else")
    private val config by opt("config", CONFIG_HELP, "path")

    override fun given() = setOfNotNull(
        "port".takeIf { port != null }, "tunnel".takeIf { tunnel != null }, "render-url".takeIf { renderUrl != null },
        "take-over".takeIf { takeOver }, "write-env".takeIf { writeEnv != null }, "show-env".takeIf { showEnv }, "reset".takeIf { reset },
    )

    override fun execute(ctx: Ctx) = Dev.run(ctx, DevArgs(name, port?.toInt(), tunnel, renderUrl, takeOver, writeEnv, showEnv, reset, config))
}

class AppsStatus : Leaf("status", "Show where the app's dev version renders from: live, parked, or the serving version.") {
    private val name by optArg("name")
    private val config by opt("config", CONFIG_HELP, "path")

    override fun execute(ctx: Ctx) = Local.status(ctx, name, config)
}

class AppsPin : Leaf("pin", "Add an app to platform.toml (and its example), by id or by name.") {
    private val app by arg("app")
    private val dir by opt("path", "The project directory; needed for a name", "dir")
    private val config by opt("config", CONFIG_HELP, "path")

    override fun execute(ctx: Ctx) = Local.pin(ctx, app, dir, config)
}

class AppsUnpin : Leaf("unpin", "Remove an app's row from platform.toml; the example changes by review.") {
    private val name by arg("name")
    private val config by opt("config", CONFIG_HELP, "path")

    override fun execute(ctx: Ctx) = Local.unpin(ctx, name, config)
}

class AppsSubmit : Leaf("submit", "Submit a version of an app for review, to make it public.") {
    private val id by arg("id")
    private val version by need("version", "The version to submit", "semver")

    override fun execute(ctx: Ctx) = Apps.submit(ctx, id, version)
}

/** `apps versions list <id>`. */
class VersionsList : Leaf("list", "List an app's versions.") {
    private val id by arg("id")

    override fun execute(ctx: Ctx) = Apps.listVersions(ctx, id)
}

/** `apps versions publish|withdraw <id> <semver>`. */
class VersionOp(name: String, summary: String, private val op: Op) : Leaf(name, summary) {
    private val id by arg("id")
    private val semver by arg("semver")

    override fun execute(ctx: Ctx) = Apps.versionOp(ctx, op, id, semver)
}

object AppsTree {
    fun build(): Topic = Topic(
        "apps",
        "Apps that render cards inside Lingara.",
        AppsCreate(), AppsList(), GetLeaf(Kind.APP, "Show one app."), AppsUpdate(), AppsDev(), AppsStatus(), AppsPin(), AppsUnpin(),
        DeleteLeaf(Kind.APP, "Delete an app, or one of its versions.", Deletes.naming("consequence_app")),
        AppsSubmit(),
        Topic(
            "versions",
            "An app's versions.",
            VersionsList(),
            VersionOp("publish", "Publish an approved version, or a private app's draft.", Op.PUBLISH),
            VersionOp("withdraw", "Withdraw a version.", Op.WITHDRAW),
        ),
    )
}
