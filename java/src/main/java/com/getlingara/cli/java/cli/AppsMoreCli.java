package com.getlingara.cli.java.cli;

import com.getlingara.cli.java.Ctx;
import com.getlingara.cli.java.cli.Choices.TunnelKind;
import com.getlingara.cli.java.cli.Mixins.ConfigOption;
import com.getlingara.cli.java.cli.Mixins.IdArg;
import com.getlingara.cli.java.commands.apps.Apps;
import com.getlingara.cli.java.commands.apps.Local;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** The rest of {@code lingara apps …}: {@code dev}, the local verbs and the version verbs (16.10.26ae D7, D9). */
public final class AppsMoreCli {
    private static final String[] NOT_WITH_RESET = {"--port", "--tunnel", "--render-url", "--take-over", "--write-env", "--show-env"};

    private AppsMoreCli() {}

    @Command(name = "dev", description = "Run the app behind a tunnel and point your private dev version at it, until Ctrl+C.")
    public static final class Dev implements Verb {
        @Parameters(index = "0", arity = "0..1", paramLabel = "<name>")
        public String name;

        @Option(names = "--port", paramLabel = "n", converter = Choices.Port.class, description = "The port the app listens on; default the row's")
        public Integer port;

        @Option(names = "--tunnel", converter = TunnelKind.Conv.class, completionCandidates = TunnelKind.Of.class,
                description = "The tunnel to open; none needs --render-url")
        public TunnelKind tunnel;

        @Option(names = "--render-url", paramLabel = "https-url", description = "With --tunnel none, the URL the dev version renders from")
        public String renderUrl;

        @Option(names = "--take-over", description = "End another terminal's session on this app first")
        public boolean takeOver;

        @Option(names = "--write-env", paramLabel = "file", description = "Also write the session's variables to this gitignored file, mode 0600")
        public Path writeEnv;

        @Option(names = "--show-env", description = "List the names of the variables the app receives")
        public boolean showEnv;

        @Option(names = "--reset", description = "Return your install to the serving version, and do nothing else")
        public boolean reset;

        @Mixin
        public ConfigOption config;

        @Override
        public void run(Ctx ctx) {
            com.getlingara.cli.java.commands.apps.Dev.run(ctx, this);
        }

        @Override
        public List<String[]> conflicts() {
            List<String[]> out = new ArrayList<>();
            for (String other : NOT_WITH_RESET) {
                out.add(new String[] {"--reset", other});
            }
            return out;
        }
    }

    @Command(name = "status", description = "Show where the app's dev version renders from: live, parked, or the serving version.")
    public static final class Status implements Verb {
        @Parameters(index = "0", arity = "0..1", paramLabel = "<name>")
        public String name;

        @Mixin
        public ConfigOption config;

        @Override
        public void run(Ctx ctx) {
            Local.status(ctx, name, config.config);
        }
    }

    @Command(name = "pin", description = "Add an app to platform.toml (and its example), by id or by name.")
    public static final class Pin implements Verb {
        @Parameters(index = "0", paramLabel = "<app>")
        public String app;

        @Option(names = "--path", paramLabel = "dir", description = "The project directory; needed for a name")
        public Path path;

        @Mixin
        public ConfigOption config;

        @Override
        public void run(Ctx ctx) {
            Local.pin(ctx, app, path, config.config);
        }
    }

    @Command(name = "unpin", description = "Remove an app's row from platform.toml; the example changes by review.")
    public static final class Unpin implements Verb {
        @Parameters(index = "0", paramLabel = "<name>")
        public String name;

        @Mixin
        public ConfigOption config;

        @Override
        public void run(Ctx ctx) {
            Local.unpin(ctx, name, config.config);
        }
    }

    @Command(name = "submit", description = "Submit a version of an app for review, to make it public.")
    public static final class Submit implements Verb {
        @Parameters(index = "0", paramLabel = "<id>")
        public String id;

        @Option(names = "--version", paramLabel = "semver", required = true, description = "The version to submit")
        public String version;

        @Override
        public void run(Ctx ctx) {
            Apps.submit(ctx, id, version);
        }
    }

    @Command(name = "versions", description = "An app's versions.", subcommands = {VersionsList.class, Publish.class, Withdraw.class})
    public static final class Versions {}

    @Command(name = "list", description = "List an app's versions.")
    public static final class VersionsList implements Verb {
        @Mixin
        public IdArg arg;

        @Override
        public void run(Ctx ctx) {
            Apps.listVersions(ctx, arg.id);
        }
    }

    @Command(name = "publish", description = "Publish an approved version, or a private app's draft.")
    public static final class Publish implements Verb {
        @Parameters(index = "0", paramLabel = "<id>")
        public String id;

        @Parameters(index = "1", paramLabel = "<semver>")
        public String semver;

        @Override
        public void run(Ctx ctx) {
            Apps.publish(ctx, id, semver);
        }
    }

    @Command(name = "withdraw", description = "Withdraw a version.")
    public static final class Withdraw implements Verb {
        @Parameters(index = "0", paramLabel = "<id>")
        public String id;

        @Parameters(index = "1", paramLabel = "<semver>")
        public String semver;

        @Override
        public void run(Ctx ctx) {
            Apps.withdraw(ctx, id, semver);
        }
    }
}
