package com.getlingara.cli.java.cli;

import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.Tree;
import com.getlingara.cli.java.commands.SessionCommands;
import com.getlingara.cli.java.core.Assets;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

/**
 * The picocli tree (ADR 16.10.26af D2): {@code spec/cli.schema.json}'s commands, args and flags,
 * hand-written as annotated classes, which {@code make check-tree-java} diffs against {@code
 * lingara __tree}. Summaries are the schema's.
 *
 * <p>{@code mixinStandardHelpOptions} is on this root only, so {@code -V} is the root's alone
 * (D9); every other command gets {@code -h/--help} from {@code Cli}. The two global flags are
 * {@code INHERIT}-scoped, so they parse at any depth, as the schema's {@code global_flags} say.
 */
@Command(
        name = "lingara",
        mixinStandardHelpOptions = true,
        versionProvider = Root.Version.class,
        description = "lingara, the Lingara developer CLI",
        subcommands = {
            Root.Login.class,
            Root.Logout.class,
            Root.Whoami.class,
            AppsCli.class,
            EmbedCli.class,
            ClientCli.class,
            WebhookCli.class,
            CanvasCli.class,
            Root.TreeCmd.class
        })
public final class Root {
    @Option(names = "--json", scope = ScopeType.INHERIT, description = "Machine-readable output on stdout")
    public boolean json;

    @Option(names = "--profile", paramLabel = "name", scope = ScopeType.INHERIT, description = "The stored credentials to use")
    public String profile;

    /** {@code lingara <VERSION> (java)}, the rule 16.10.26af D1 adds. */
    public static final class Version implements IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {line()};
        }

        public static String line() {
            return "lingara " + Assets.version() + " (java)";
        }
    }

    @Command(name = "login", description = "Sign in through the browser, or with a device code.")
    public static final class Login implements Verb {
        @Option(names = "--device", description = "Sign in with a code shown here, for a machine with no browser")
        public boolean device;

        @Override
        public void run(Ctx ctx) {
            SessionCommands.login(ctx, device);
        }
    }

    @Command(name = "logout", description = "Revoke and forget the stored credentials.")
    public static final class Logout implements Verb {
        @Override
        public void run(Ctx ctx) {
            SessionCommands.logout(ctx);
        }
    }

    @Command(name = "whoami", description = "Show the developer the credentials belong to.")
    public static final class Whoami implements Verb {
        @Override
        public void run(Ctx ctx) {
            SessionCommands.whoami(ctx);
        }
    }

    @Command(name = "__tree", hidden = true, description = "The registered command tree as JSON, for make check-tree.")
    public static final class TreeCmd implements Verb {
        @Override
        public void run(Ctx ctx) {
            ctx.out.jsonValue(Tree.tree());
        }
    }
}
