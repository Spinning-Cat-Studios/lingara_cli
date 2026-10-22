// The Clikt tree (ADR 16.10.26af D2): `spec/cli.schema.json`'s commands,
// args and flags, hand-written, which `make check-tree` diffs against
// `lingara __tree`. Summaries are the schema's. The root carries the one
// `--version` (`-V` too, as clap's), which prints `lingara <VERSION>
// (kotlin)`, and turns off Clikt's `@argfile` expansion, which would read a
// file for any argument that starts with `@`.
package com.getlingara.cli.kotlin.cli

import com.getlingara.cli.kotlin.app.Ctx
import com.getlingara.cli.kotlin.commands.SessionCommands
import com.getlingara.cli.kotlin.core.Assets
import com.getlingara.cli.kotlin.core.Json
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption

class Root : Node("lingara", "lingara, the Lingara developer CLI") {
    /** The leaf parsing chose; set by [Leaf.run]. */
    var chosen: Leaf? = null

    init {
        versionOption(Assets.version, names = setOf("--version", "-V"), message = { "lingara $it (kotlin)" })
        configureContext { readArgumentFile = null }
    }

    override fun aliases(): Map<String, List<String>> = mapOf("client" to listOf("oauth_client"))

    override fun run() = Unit

    companion object {
        fun build(): Root = Root().subcommands(
            Login(),
            Logout(),
            Whoami(),
            AppsTree.build(),
            ObjectTrees.embed(),
            ObjectTrees.oauthClient(),
            ObjectTrees.webhook(),
            CanvasTree.build(),
            TreeLeaf(),
        )
    }
}

class Login : Leaf("login", "Sign in through the browser, or with a device code.") {
    private val device by switch("device", "Sign in with a code shown here, for a machine with no browser")

    override fun execute(ctx: Ctx) = SessionCommands.login(ctx, device)
}

class Logout : Leaf("logout", "Revoke and forget the stored credentials.") {
    override fun execute(ctx: Ctx) = SessionCommands.logout(ctx)
}

class Whoami : Leaf("whoami", "Show the developer the credentials belong to.") {
    override fun execute(ctx: Ctx) = SessionCommands.whoami(ctx)
}

/** `lingara __tree`: the registered tree as JSON, for `make check-tree`; hidden. */
class TreeLeaf : Leaf("__tree", "The registered command tree as JSON, for `make check-tree`.") {
    override val hiddenFromHelp: Boolean = true

    override fun execute(ctx: Ctx) = ctx.out.print(Json.write(Tree.of(Root.build())) + "\n")
}
